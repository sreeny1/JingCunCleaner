# 更新日志

本项目版本号遵循 `主版本.次版本`（如 `2.2`），versionCode 单调递增。
每次发布在 GitHub Releases 创建对应 tag（如 `v2.2`）并上传 APK。

## [v2.2] - 2026-08-02

### 新增

#### OTA 在线更新（GitHub Releases）
- App 启动时静默检查新版本，有更新自动弹窗提示
- 设置页「检查更新」按钮，手动触发检查并反馈状态
- 全局更新弹窗：展示版本号 / 更新说明 / 安装包大小
- 下载进度条（带百分比与字节数实时显示）
- **引导式安装**：通过 `FileProvider` 生成 `content://` URI，调起系统 `PackageInstaller` 由用户确认安装，无需 root 或系统签名
- 版本号点分比较算法（支持 `2.10.1` 等多段版本号，避免 `2.10 < 2.2` 的字符串比较坑）
- 网络超时与异常容错（连接 10s / 读取 15s，失败给用户友好提示）

#### 高刷新率适配
- 默认 60Hz；系统支持更高（90/120/144Hz）时自动跟随最高帧率
- 仅在「与当前分辨率相同的 mode」中挑选最高刷新率，避免误改分辨率
- 60Hz 设备保持系统默认不额外耗电
- 通过 `Window.attributes.preferredDisplayModeId` 设置，无需 manifest 权限

### 改进
- `AndroidManifest.xml` 新增 `INTERNET` / `ACCESS_NETWORK_STATE` 权限
- 新增 `FileProvider` 声明与 `res/xml/file_provider_paths.xml`，用于安全分享 OTA APK
- `CleanerViewModel` 新增 OTA 状态机（IDLE / CHECKING / AVAILABLE / DOWNLOADING / DOWNLOADED / ERROR）

### 修复
- `UpdateChecker` 版本比较 bug：原实现将 `v2.2` 转 `Long` 变成 `2`，比当前 versionCode=3 小会误判为无更新；改为基于 `versionName` 的点分比较

### 技术细节
- `minSdk` 31、`targetSdk` 36、`compileSdk` 36
- versionCode=4，versionName=2.2
- 构建工具：Gradle 8.14.4 + Kotlin 2.0.21 + Compose BOM

---

## [v2.1] - 2026-08-01

### 新增
- 已安装应用存储占用排行（按总大小降序展示）
- 支持降级模式：用户拒绝「所有文件访问权限」时切换到运行时权限，避免功能完全不可用
- 应用排行支持「使用情况访问」权限引导授权

### 改进
- `PermissionAdapter` 抽象统一处理标准/降级两种权限模式
- `AppStorageScanner` 在降级模式下使用 `StorageStatsManager` 替代直接文件访问

---

## [v2.0] - 2026-07-31

### 新增
- APK 整理功能：扫描设备内所有 `.apk`，按「应用名+版本号」重命名归档到 `/storage/emulated/0/apk整理/`
- 整理模式可选：复制（保留原文件）或移动（删除原文件）
- 解析未安装 APK 元信息时正确设置 `sourceDir`，避免 `loadLabel` 返回包名

### 改进
- 重构为 Jetpack Compose + Material 3 + NavigationSuiteScaffold
- 支持宽屏自适应（手机用 BottomBar，平板/车机用 NavigationRail）
- 引入 `WindowSizeClass` 区分设备形态

---

## [v1.1] - 2026-07-30

### 新增
- 回收站机制：删除文件先移入 `.JingCunTrash/`，元数据写入 `index.json`
- 一键恢复 / 单条恢复 / 彻底删除 / 清空回收站
- 过期自动清理（按保留天数，支持 3/7/14/30 天）
- 屏幕方向设置（自动 / 横屏 / 竖屏）

### 改进
- `DataStore Preferences` 替代 SharedPreferences 持久化设置
- 设置页新增「扫描后直接清理」「空文件夹扫描范围」「回收站保留天数」等选项

---

## [v1.0] - 2026-07-29

### 首次发布
- 垃圾文件扫描引擎（5 类：应用缓存 / 临时日志 / APK 残留 / 缩略图缓存 / 空文件夹）
- 自底向上空文件夹判定（仅标记空子树顶端，避免误删父目录链）
- 公共目录保护名单（DCIM、Pictures、Music 等系统目录即使为空也不删）
- 扫描进度分 4 阶段递增显示，避免卡在固定百分比
- 一键清理（按分类勾选，先删文件再按深度倒序删目录）
- Material 3 主题 + 圆角卡片 UI
