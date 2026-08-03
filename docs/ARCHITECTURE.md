# 架构与开发文档

## 总体架构

```
┌─────────────────────────────────────────────────────────┐
│                    UI 层（Compose）                       │
│  AppRoot → NavHost → HomeScreen / ApkOrganizerScreen /   │
│                    TrashScreen / SettingsScreen / About  │
│                          │                              │
│              UpdateDialog（全局 OTA 弹窗）                │
└──────────────────────────┬──────────────────────────────┘
                           │ StateFlow 收集
┌──────────────────────────┴──────────────────────────────┐
│              CleanerViewModel（AndroidViewModel）         │
│  持有：ScanEngine / TrashManager / ApkOrganizer /         │
│       AppStorageScanner / SettingsRepository /            │
│       UpdateChecker / UpdateInstaller                     │
│  状态：UiState / apkState / appStorageState / updateState │
└──────────────────────────┬──────────────────────────────┘
                           │ 协程调用
┌──────────────────────────┴──────────────────────────────┐
│                    业务层（suspend 函数）                 │
│  ScanEngine.scan/clean  TrashManager.moveToTrash/restore │
│  ApkOrganizer.scanAll/organizeAll                        │
│  UpdateChecker.checkForUpdate  UpdateInstaller.download  │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────┐
│                    数据层                                │
│  SettingsRepository（DataStore Preferences）              │
│  TrashManager.index.json（JSON 元数据文件）               │
│  GitHub Releases API（OTA 源）                            │
└─────────────────────────────────────────────────────────┘
```

## 关键设计决策

### 1. 单 ViewModel + 多 StateFlow

整个 App 用一个 `CleanerViewModel` 持有所有业务对象，避免多 ViewModel 间传递数据的复杂性。每个功能模块暴露独立的 `StateFlow`：
- `state: StateFlow<UiState>` — 扫描/清理状态
- `apkState: StateFlow<ApkUiState>` — APK 整理状态
- `appStorageState: StateFlow<AppStorageUiState>` — 应用排行状态
- `updateState: StateFlow<UpdateUiState>` — OTA 更新状态
- `trashItems: StateFlow<List<TrashItem>>` — 回收站列表

UI 通过 `collectAsState()` 订阅，状态变化自动重组。

### 2. 协程 + Dispatchers.IO

所有文件/网络操作都用 `withContext(Dispatchers.IO)` 包裹，避免阻塞主线程。`viewModelScope` 管理协程生命周期，ViewModel 销毁时自动取消。

### 3. 回收站用 JSON 而非 Room

回收站元数据存于 `.JingCunTrash/index.json`，用 `JSONArray` 手动序列化。原因：
- 避免引入 Room 依赖，保持 APK 体积
- 回收站条目数量有限（通常 < 1000），JSON 读写性能足够
- 文件可被外部工具直接查看，便于调试

### 4. 扫描引擎分 4 阶段

`ScanEngine.scan()` 把扫描分成 4 个阶段，每阶段完成推进 25%：
1. 本应用自身缓存（0~25%）
2. 各应用公共缓存目录 `Android/data` `Android/media`（25~50%）
3. 公共区域文件遍历（50~75%）
4. 空文件夹扫描（75~100%）

UI 根据当前阶段文案映射到对应进度区间，避免卡在固定百分比。

### 5. 空文件夹「自底向上」判定

扫描所有目录后，按路径长度倒序（叶子目录先处理），计算每个目录的 `hasFile[dir]`（含子树是否含文件）。然后标记「空子树顶端」：目录自身为空，但父目录非空。这样避免误删父目录链。

保护名单（DCIM、Pictures、Music 等系统目录）即使为空也不删，因为系统会重建或依赖。

### 6. APK 残留判定

`isApkResidual()` 通过 `PackageManager.getPackageArchiveInfo()` 解析 APK 的包名和 versionCode，与已安装版本对比：已安装版本 ≥ 安装包版本 → 视为冗余残留。未安装的 APK 不算残留，保留以便用户安装。

### 7. 降级权限模式

`PermissionAdapter` 抽象两种权限路径：
- **标准模式**：`MANAGE_EXTERNAL_STORAGE`（Android 11+），可遍历整个公共存储
- **降级模式**：运行时 `READ_EXTERNAL_STORAGE`，仅扫描有限区域

UI 根据 `isLegacyMode()` 决定显示授权按钮还是直接扫描，避免功能完全不可用。

### 8. OTA 状态机

`UpdateUiState.phase` 是有限状态机：

```
IDLE ──checkForUpdate──> CHECKING ──┬──有新版本──> AVAILABLE
                                    │                   │
                                    └──无更新──> IDLE    │
                                                        │
                                              downloadUpdate
                                                        │
                                                        ▼
                            ERROR <──失败── DOWNLOADING
                                        │           │
                                        │       下载完成
                                        │           │
                                        ▼           ▼
                                      IDLE      DOWNLOADED
                                                    │
                                              installUpdate
                                                    │
                                                    ▼
                                          （调起系统安装器）
```

UI 根据 phase 决定显示什么：
- `AVAILABLE` → 更新说明弹窗（立即下载 / 稀后）
- `DOWNLOADING` → 进度条弹窗（取消）
- `DOWNLOADED` → 安装确认弹窗（立即安装 / 稀后）
- `IDLE` / `CHECKING` / `ERROR` → 不弹窗，由设置页卡片显示状态

## 模块详解

### ScanEngine

`com.jingcun.cleaner.scanner.ScanEngine`

核心方法：
- `scan(emptyFolderScope, onProgress): Result` — 扫描垃圾文件
- `clean(items, recycleBinEnabled, onProgress): CleanResult` — 清理选中项

垃圾分类（`JunkCategory`）：
- `APP_CACHE` — 应用缓存（本应用 + 各应用公共缓存目录）
- `TEMP_LOG` — 临时/日志文件（扩展名 `tmp` `temp` `log` `bak` `old`）
- `APK_RESIDUAL` — APK 残留（已安装且版本冗余的安装包）
- `THUMBNAIL` — 缩略图缓存（`.thumbnails` `.thumbs` 等）
- `SYSTEM_JUNK` — 系统残留文件（.DS_Store、desktop.ini、thumbs.db、LOST.DIR、广告缓存等，参考 SD Maid SE 规则）
- `EMPTY_FOLDER` — 空文件夹（自底向上判定）

扫描排除目录（`appGeneratedDirs`）：
- `.JingCunTrash/` — 回收站目录，避免误删待恢复文件
- `apk整理/` — APK 整理输出目录，避免误删已整理的 APK

### TrashManager

`com.jingcun.cleaner.trash.TrashManager`

回收站根目录：`/storage/emulated/0/.JingCunTrash/`
元数据文件：`.JingCunTrash/index.json`

`TrashItem` 字段：`id` `originalPath` `trashPath` `name` `size` `isDirectory` `deletedAt`

核心方法：
- `moveToTrash(sourcePath): TrashItem?` — 移入回收站
- `restore(item): Boolean` — 恢复（冲突自动加 `_restored` 后缀）
- `restoreAll(): Int` — 一键恢复
- `deletePermanently(item): Boolean` — 彻底删除
- `clearAll(): Int` — 清空
- `cleanExpired(retentionDays): Int` — 过期清理
- `list(): List<TrashItem>` — 按删除时间倒序列出

文件移动优先 `renameTo`（同卷快），失败则复制+删除（跨卷）。

### ApkOrganizer

`com.jingcun.cleaner.apk.ApkOrganizer`

目标目录：`/storage/emulated/0/apk整理/`

关键点：解析未安装 APK 时必须设置 `applicationInfo.sourceDir` 和 `publicSourceDir`，否则 `loadLabel()` 返回包名而非应用名。

整理模式（`ApkOrganizeMode`）：
- `MOVE` — 复制后删除原文件
- `COPY` — 仅复制保留原文件

### UpdateChecker

`com.jingcun.cleaner.update.UpdateChecker`

请求 `https://api.github.com/repos/{owner}/{repo}/releases/latest`，解析：
- `tag_name` — 版本号（去掉 `v` 前缀）
- `body` — 更新说明
- `assets[].browser_download_url` — APK 下载地址
- `assets[].size` — APK 文件大小

版本比较用 `versionToLong()`：把点分版本号转为可比较的长整型（每段限定 0~9999，最多 4 段）。例：`2.1 → 20001`，`2.10.1 → 2010001`。

### UpdateInstaller

`com.jingcun.cleaner.update.UpdateInstaller`

下载目录：`context.externalCacheDir/updates/`（应用专属，无需权限）

下载流程：`HttpURLConnection` 流式下载，8KB 缓冲区，实时回调 `DownloadProgress(downloaded, total)`。

安装流程（引导式）：
1. `FileProvider.getUriForFile()` 生成 `content://` URI
2. `Intent.ACTION_VIEW` + type `application/vnd.android.package-archive`
3. 添加 `FLAG_GRANT_READ_URI_PERMISSION`
4. `startActivity()` 调起系统安装器
5. 用户在系统页确认安装

## 构建配置

### 签名

`app/build.gradle.kts` 配置了 release 签名：
- `storeFile = file("../keystore/release.jks")`
- `storePassword = "jingcun123"`
- `keyAlias = "jingcun"`
- `keyPassword = "jingcun123"`

> ⚠️ keystore 文件和密码已加入 `.gitignore`，不入库。clone 后需自行生成或向作者索取。

### 依赖版本

依赖版本统一管理在 `gradle/libs.versions.toml`，主要依赖：
- AndroidX Core KTX
- Lifecycle ViewModel Compose
- Activity Compose
- Compose BOM + Material 3
- Material Icons Extended
- Core SplashScreen
- Navigation Compose
- DataStore Preferences
- Material3 Adaptive Navigation Suite
- WindowSizeClass

## 开发环境

- JDK 17
- Gradle 8.14.4
- Kotlin 2.0.21
- Android SDK 36（compileSdk）
- 最低 minSdk 31（Android 12）

## 调试技巧

### 查看回收站内容

```bash
adb shell ls -la /storage/emulated/0/.JingCunTrash/
adb shell cat /storage/emulated/0/.JingCunTrash/index.json
```

### 触发 OTA 检查

启动 App 即自动检查。手动触发：设置页 → 「检查更新」按钮。

### 模拟旧版本测试 OTA

把 `build.gradle.kts` 的 `versionName` 改成 `2.1`，构建安装后启动，会检测到 GitHub 上的 v2.2 Release 并提示更新。

### 查看日志

```bash
adb logcat | grep JingCun
```
