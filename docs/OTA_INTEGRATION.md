# OTA 在线更新集成指南

## 设计目标

- 用户无需手动去网页下载 APK，App 内自动检测 + 下载 + 引导安装
- 更新源用 GitHub Releases（免费、稳定、无需自建服务器）
- 启动时静默检查（有新版本才弹窗）+ 设置页手动检查按钮
- 引导式安装（调起系统 PackageInstaller，用户确认，无需 root）

## 整体流程

```
App 启动
   │
   ▼
CleanerViewModel.init() → checkForUpdate(silent=true)
   │
   ▼
UpdateChecker.checkForUpdate()
   │
   ▼ HTTP GET
https://api.github.com/repos/sreeny1/JingCunCleaner/releases/latest
   │
   ▼ 解析 JSON
UpdateInfo(versionName, versionCode, releaseNotes, downloadUrl, fileSize)
   │
   ▼ 版本比较
newVerNum > currentVerNum ?
   │                │
   │是              │否
   ▼                ▼
phase=AVAILABLE   phase=IDLE（静默）/ 提示「已是最新」（手动）
   │
   ▼ UI 弹窗
UpdateDialog 显示版本号 + 更新说明
   │
   ▼ 用户点「立即下载」
phase=DOWNLOADING
   │
   ▼ UpdateInstaller.download() 流式下载
externalCacheDir/updates/jingcun_update.apk
   │
   ▼ 下载完成
phase=DOWNLOADED
   │
   ▼ 用户点「立即安装」
UpdateInstaller.install()
   │
   ▼ FileProvider.getUriForFile()
content://com.jingcun.cleaner.fileprovider/updates/jingcun_update.apk
   │
   ▼ Intent.ACTION_VIEW + type=application/vnd.android.package-archive
   │
   ▼
系统 PackageInstaller 弹出安装确认页
   │
   ▼ 用户点「安装」
安装完成，新版本覆盖旧版本
```

## 关键代码位置

| 文件 | 职责 |
| --- | --- |
| [UpdateChecker.kt](../app/src/main/java/com/jingcun/cleaner/update/UpdateChecker.kt) | GitHub Releases API 请求 + 版本比较 |
| [UpdateInstaller.kt](../app/src/main/java/com/jingcun/cleaner/update/UpdateInstaller.kt) | APK 下载 + FileProvider 引导安装 |
| [CleanerViewModel.kt](../app/src/main/java/com/jingcun/cleaner/ui/CleanerViewModel.kt) | OTA 状态机 + 协程调度 |
| [SettingsScreen.kt](../app/src/main/java/com/jingcun/cleaner/ui/SettingsScreen.kt) | 「检查更新」卡片 UI |
| [AppRoot.kt](../app/src/main/java/com/jingcun/cleaner/ui/AppRoot.kt) | 全局更新弹窗 |
| [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) | INTERNET 权限 + FileProvider 声明 |
| [file_provider_paths.xml](../app/src/main/res/xml/file_provider_paths.xml) | FileProvider 共享路径配置 |

## 版本比较算法

`UpdateChecker.versionToLong()` 把点分版本号转为可比较的长整型：

```kotlin
private fun versionToLong(version: String): Long {
    val parts = version.split(".", "_", "-")
        .mapNotNull { it.toIntOrNull() }
        .take(4)
    if (parts.isEmpty()) return 0L
    var result = 0L
    for (part in parts) {
        result = result * 10000 + part.coerceIn(0, 9999)
    }
    return result
}
```

示例：
- `2.1` → `20001`
- `2.2` → `20002`
- `2.10` → `20100`（大于 `2.2` 的 `20002`，正确）
- `2.10.1` → `2010001`
- `2.1.0` → `200010000`（前导零填充，不影响比较）

每段限定 0~9999，最多 4 段，覆盖绝大多数版本号场景。

## FileProvider 配置

### AndroidManifest.xml

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths" />
</provider>
```

### res/xml/file_provider_paths.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- OTA APKs 存于 externalCacheDir/updates -->
    <external-cache-path name="updates" path="updates/" />
    <cache-path name="cache" path="." />
</paths>
```

## 引导式安装 Intent

```kotlin
val uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.fileprovider",
    apkFile,
)
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, "application/vnd.android.package-archive")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
context.startActivity(intent)
```

关键点：
- 必须用 `content://` URI（Android 7.0+ 禁止 `file://` URI 共享）
- 必须加 `FLAG_GRANT_READ_URI_PERMISSION`，否则系统安装器无权读取 APK
- 用 `resolveActivity()` 校验系统存在能处理该 Intent 的 Activity

## 状态机设计

`UpdateUiState.phase` 有限状态机：

| Phase | 触发条件 | UI 表现 |
| --- | --- | --- |
| `IDLE` | 初始 / 检查完无更新 / 关闭弹窗 | 设置页卡片显示「已是最新」或默认提示 |
| `CHECKING` | `checkForUpdate()` 调用 | 设置页按钮变「检查中…」+ 转圈 |
| `AVAILABLE` | 检查到新版本 | 全局弹窗：版本号 + 更新说明 + 立即下载/稀后 |
| `DOWNLOADING` | `downloadUpdate()` 调用 | 全局弹窗：进度条 + 取消 |
| `DOWNLOADED` | 下载完成 | 全局弹窗：立即安装/稀后 |
| `ERROR` | 检查/下载/安装失败 | 设置页卡片显示错误信息 |

`silent` 字段区分启动时静默检查 vs 用户手动触发：
- `silent=true`：无更新时不提示（避免打扰）
- `silent=false`：无更新时提示「已是最新版本」（给用户反馈）

## 发版流程

详见 [RELEASE_PROCESS.md](./RELEASE_PROCESS.md)。

简述：
1. 改 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`
2. `gradle assembleRelease` 构建 APK
3. 在 GitHub 创建新 tag（如 `v2.3`）的 Release，上传 APK
4. 装了旧版本的用户启动 App 时自动检测到新版本

## 网络容错

| 场景 | 处理 |
| --- | --- |
| 网络不通 | `HttpURLConnection` 连接超时 10s，返回 null，UI 显示「检查失败」 |
| API 限流（403） | 返回 null，UI 显示「检查失败」（GitHub 未授权限流 60 次/小时） |
| Release 无 APK 资产 | `parseRelease()` 返回 null，视为无更新 |
| tag_name 格式异常 | `versionToLong()` 返回 0，视为无更新 |
| 下载中断 | 文件已删除，返回 null，UI 显示「下载失败，请检查网络后重试」 |
| 系统无安装器 | `resolveActivity()` 返回 null，UI 显示「无法调起系统安装器」 |

## 测试验证

### 验证 OTA 检查 API

```bash
curl -s -H "Accept: application/vnd.github+json" \
  -H "User-Agent: JingCunCleaner-OTA" \
  https://api.github.com/repos/sreeny1/JingCunCleaner/releases/latest | python3 -m json.tool
```

应返回包含 `tag_name`、`body`、`assets[].browser_download_url` 的 JSON。

### 端到端测试

1. 安装旧版本（如 v2.1）APK
2. 启动 App，等待几秒
3. 应自动弹出「发现新版本 v2.2」弹窗
4. 点「立即下载」，观察进度条
5. 下载完成后点「立即安装」
6. 系统弹出安装确认页，点「安装」
7. 安装完成后启动 App，版本号应为 v2.2
