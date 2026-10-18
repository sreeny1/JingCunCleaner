# APK归纳

> 面向 Android 车机、平板、手机的 APK 管理工具。扫描归纳存储中的 APK → 按应用名+版本号统一命名 → 提取已安装应用安装包 → OTA 在线更新，全流程离线可用，无需注册账号。

- 当前版本：**v3.0**（versionCode=10）
- 最低系统：Android 12（API 31）
- 目标系统：Android 16（API 36）
- 界面框架：Jetpack Compose + Material 3（蓝白科技风主题）
- OTA 源：GitHub Releases（本仓库）

## 核心功能

| 模块 | 说明 |
| --- | --- |
| APK 归纳 | 扫描内置存储与 U 盘/SD 卡中所有 `.apk`，统一移动到 `/APK归纳/`，按「应用名+版本号」重命名（如 `微信8.12.1.apk`） |
| 应用提取 | 列出所有已安装应用（含系统应用），提取 APK 到 `/APK归纳/提取/`；支持搜索、筛选、单个/批量提取 |
| 提取权限链 | 直接读取 → Shizuku（ADB 权限）→ Root 三级降级；均无权限时明确提示，列表始终可见 |
| OTA 更新 | 启动自动检查 + 设置页手动检查，GitHub Releases 下载并引导式安装 |
| 外观主题 | 日间 / 夜间 / 跟随系统，蓝白科技风配色 |
| 车机适配 | 横屏自动切换侧边导航，授权页精简的车机自动降级存储权限，主按钮 52dp 触控友好 |
| 屏幕方向 | 自动 / 强制横屏 / 强制竖屏，适合无重力感应的车机 |

## 下载安装

### 方式 1：从 GitHub Release 安装（推荐）

访问 [Releases 页面](https://github.com/sreeny1/JingCunCleaner/releases/latest)，下载最新版 APK 后手动安装。

### 方式 2：从源码构建

```bash
git clone https://github.com/sreeny1/JingCunCleaner.git
cd JingCunCleaner
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 构建需要本地 `local.properties` 配置 `sdk.dir`，且需要 JDK 17。

## 权限说明

| 权限 | 用途 | 必需 |
| --- | --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | 遍历公共存储扫描与归纳 APK | 是（标准模式） |
| `READ/WRITE_EXTERNAL_STORAGE` | Android 12 及以下 / 精简车机兼容 | 否（降级模式） |
| `QUERY_ALL_PACKAGES` | 读取已安装应用列表（提取功能） | 是 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | OTA 检查更新与下载 APK | 是 |
| `REQUEST_INSTALL_PACKAGES` | OTA 下载后调起系统安装器 | 是 |
| `moe.shizuku.manager.permission.API_V23` | Shizuku（ADB 权限）提取通道 | 否（可选） |

> **提取权限说明**：系统应用 APK 位于 `/system` 等全局可读分区，可直接提取；用户应用 APK（`/data/app`）受 Android 11+ 系统限制，普通应用无法直接读取。提取时按「直接读取 → Shizuku → Root」顺序降级尝试；若设备未开启 ADB 权限（Shizuku）也无 Root，会提示暂时无法提取，应用列表仍可正常查看。

## 项目结构

```
JingCunCleaner/
├── app/
│   ├── build.gradle.kts                 # 应用模块配置（versionCode/versionName）
│   └── src/main/
│       ├── AndroidManifest.xml          # 权限 + FileProvider + Shizuku Provider
│       ├── java/com/jingcun/cleaner/
│       │   ├── JingCunApp.kt            # Application 入口
│       │   ├── MainActivity.kt          # Activity + 高刷/方向适配
│       │   ├── apk/
│       │   │   ├── ApkOrganizer.kt      # APK 归纳引擎（多存储卷扫描 + 重命名）
│       │   │   └── AppExtractor.kt      # 应用提取引擎（直接读/Shizuku/root 降级链）
│       │   ├── data/                    # DataStore 设置持久化
│       │   ├── ui/
│       │   │   ├── AppRoot.kt           # 三页导航骨架 + OTA 弹窗
│       │   │   ├── OrganizeScreen.kt    # APK 归纳页（主页）
│       │   │   ├── ExtractScreen.kt     # 应用提取页
│       │   │   ├── SettingsScreen.kt    # 设置 + 检查更新 + 关于
│       │   │   ├── ApkViewModel.kt      # 全局 ViewModel（含 OTA 状态机）
│       │   │   └── theme/               # 蓝白科技风配色
│       │   ├── update/                  # OTA 更新模块
│       │   │   ├── UpdateChecker.kt     # GitHub Releases API 检查
│       │   │   └── UpdateInstaller.kt   # 下载 + FileProvider 安装
│       │   └── util/                    # 权限适配、格式化、图标转换工具
│       └── res/
├── gradle/libs.versions.toml            # 依赖版本目录（含 Shizuku）
├── docs/                                # 架构 / OTA / 发版文档
├── keystore/release.jks                 # 签名密钥
└── settings.gradle.kts
```

## Shizuku 提取通道（可选）

用户应用提取支持 [Shizuku](https://github.com/RikkaApps/Shizuku)（开源 MIT）：

1. 在设备上安装 [Shizuku](https://github.com/RikkaApps/Shizuku/releases) 并按其文档激活：
   - 支持无线调试的设备：设备上直接配对激活
   - 车机/老设备：通过 PC `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` 激活
2. 保持 Shizuku 运行，回到本应用「提取」页，点击状态卡上的「授权」
3. 授权后即可提取全部用户应用

未激活 Shizuku 时：系统应用可正常提取，用户应用会提示暂时无法提取。

## 文档

- [更新日志](./CHANGELOG.md)
- [架构与开发文档](./docs/ARCHITECTURE.md)
- [OTA 在线更新集成指南](./docs/OTA_INTEGRATION.md)
- [高刷适配说明](./docs/HIGH_REFRESH_RATE.md)
- [发版流程](./docs/RELEASE_PROCESS.md)

## 技术参考

本项目在实现过程中借鉴了以下开源项目的设计思路：

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) — ADB 权限提取通道（MIT）
- [pass-with-high-score/universal-installer](https://github.com/pass-with-high-score/universal-installer) — APK 元信息解析（loadLabel 前设置 sourceDir）
- Android 官方 [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller) 与 [Display.Mode](https://developer.android.com/reference/android/view/Display.Mode) 文档

## License

自用工具，暂未开源授权。如需使用源码请保留原作者署名。
