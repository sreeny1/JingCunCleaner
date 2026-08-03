# 净存清理（JingCunCleaner）

> 面向 Android 车机、平板、手机的一体化存储清理工具。扫描垃圾文件 → 一键清理 → 回收站保护 → APK 整理 → OTA 在线更新，全流程离线可用，无需注册账号。

- 当前版本：**v2.7**（versionCode=9）
- 最低系统：Android 12（API 31）
- 目标系统：Android 16（API 36）
- 界面框架：Jetpack Compose + Material 3
- OTA 源：GitHub Releases（本仓库）

## 核心功能

| 模块 | 说明 |
| --- | --- |
| 垃圾扫描 | 应用缓存 / 临时日志 / APK 残留 / 缩略图缓存 / 系统残留文件 / 空文件夹，6 类分类展示 |
| 安全清理 | 内置回收站（`.JingCunTrash/`），删除先入站可恢复，过期自动清理 |
| APK 整理 | 扫描设备内所有 `.apk`，按「应用名+版本号」重命名归档到 `/apk整理/` |
| 应用排行 | 已安装应用存储占用排行（需「使用情况访问」权限） |
| OTA 更新 | 启动自动检查 + 设置页手动检查，GitHub Releases 下载并引导式安装 |
| 高刷适配 | 默认 60Hz，系统支持更高（90/120/144Hz）时自动跟随最高帧率 |
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
| `MANAGE_EXTERNAL_STORAGE` | 遍历公共存储扫描垃圾文件 | 是（标准模式） |
| `READ/WRITE_EXTERNAL_STORAGE` | Android 12 及以下兼容 | 否（降级模式） |
| `QUERY_ALL_PACKAGES` | 读取已安装应用列表判断 APK 残留 | 是 |
| `PACKAGE_USAGE_STATS` | 统计已安装应用存储占用 | 仅「应用排行」需要 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | OTA 检查更新与下载 APK | 是 |

> **降级模式**：若用户拒绝「所有文件访问权限」，App 会切换到运行时存储权限，仅扫描有限区域，避免功能完全不可用。

## 项目结构

```
JingCunCleaner/
├── app/
│   ├── build.gradle.kts                 # 应用模块配置
│   └── src/main/
│       ├── AndroidManifest.xml          # 权限 + FileProvider 声明
│       ├── java/com/jingcun/cleaner/
│       │   ├── JingCunApp.kt            # Application 入口
│       │   ├── MainActivity.kt          # Activity + 高刷/方向适配
│       │   ├── apk/                     # APK 整理 + 应用大小扫描
│       │   ├── data/                    # DataStore 设置持久化
│       │   ├── scanner/                 # 垃圾扫描引擎
│       │   ├── trash/                   # 回收站管理
│       │   ├── ui/                      # Compose 界面
│       │   │   ├── AppRoot.kt           # 导航骨架 + OTA 弹窗
│       │   │   ├── HomeScreen.kt        # 扫描/清理主页
│       │   │   ├── ApkOrganizerScreen.kt
│       │   │   ├── TrashScreen.kt
│       │   │   ├── SettingsScreen.kt    # 设置页 + 检查更新卡片
│       │   │   ├── AboutScreen.kt
│       │   │   ├── CleanerViewModel.kt  # 全局 ViewModel（含 OTA 状态机）
│       │   │   └── theme/
│       │   ├── update/                  # OTA 更新模块
│       │   │   ├── UpdateChecker.kt     # GitHub Releases API 检查
│       │   │   └── UpdateInstaller.kt   # 下载 + FileProvider 安装
│       │   └── util/                    # 权限、格式化工具
│       └── res/                         # 资源（图标、主题、FileProvider 路径）
├── gradle/libs.versions.toml            # 依赖版本目录
├── build.gradle.kts                     # 根构建脚本
└── settings.gradle.kts
```

## 文档

- [更新日志](./CHANGELOG.md)
- [架构与开发文档](./docs/ARCHITECTURE.md)
- [OTA 在线更新集成指南](./docs/OTA_INTEGRATION.md)
- [高刷适配说明](./docs/HIGH_REFRESH_RATE.md)
- [发版流程](./docs/RELEASE_PROCESS.md)

## 技术参考

本项目在实现过程中借鉴了以下开源项目的设计思路：

- [d4rken-org/sdmaid-se](https://github.com/d4rken-org/sdmaid-se) — 存储区域抽象与规则引擎
- [MythroniX24/OmniFiles](https://github.com/MythroniX24/OmniFiles) — 回收站元数据管理
- [pass-with-high-score/universal-installer](https://github.com/pass-with-high-score/universal-installer) — APK 元信息解析
- Android 官方 [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller) 与 [Display.Mode](https://developer.android.com/reference/android/view/Display.Mode) 文档

## License

自用工具，暂未开源授权。如需使用源码请保留原作者署名。
