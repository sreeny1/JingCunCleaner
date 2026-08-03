# 更新日志

本项目版本号遵循 `主版本.次版本`（如 `2.2`），versionCode 单调递增。
每次发布在 GitHub Releases 创建对应 tag（如 `v2.2`）并上传 APK。

## [v2.7.1] - 2026-08-04

### 优化（扫描进度体验）
- **扫描进度条改为渐变更新**：修复扫描时进度卡在固定值（0.05/0.30/0.55/0.80）不渐变的问题，每个阶段内按已扫描项数线性递增，进度条更流畅
- **扫描卡片新增线性进度条**：`ScanningCard` 在 `CircularProgressIndicator` 下方增加 `LinearProgressIndicator`，用户可直观看到当前阶段进度百分比
- **进度回调频率提升**：`ScanEngine.report` 从每 40 项回调一次改为每 25 项，进度更新更及时

### 优化（性能与内存）
- **SummaryCard 缓存 flatten 结果**：用 `remember(state.results)` 缓存 `flatten()` 结果，避免每次重组都创建新列表
- **CategoryCard 缓存选中项大小**：用 `remember(list, selected)` 缓存 `filter + sumOf` 结果，避免展开列表时每次重组都重新计算
- **CategoryCard 列表渲染数量优化**：展开时显示项数从 60 降为 50，减少一次性渲染的 Composable 数量
- **应用图标 Bitmap 内存优化**：`drawableToImageBitmap` 的 Bitmap 尺寸从 108px 降为 72px，单图标内存节省约 55%，在 200+ 应用列表中显著降低内存峰值

### 技术细节
- versionCode=10，versionName=2.7.1
- minSdk 31, targetSdk 36, compileSdk 36

---

## [v2.7] - 2026-08-03

### 修复（扫描误删自身目录）
- **扫描时排除本应用自身生成的目录**：`walkPublic` 和 `scanEmptyFolders` 两个扫描路径均跳过以下目录，避免误删回收站文件和 APK 整理成果
  - `.JingCunTrash/`（回收站目录）
  - `apk整理/`（APK 整理输出目录）
- 新增 `appGeneratedDirs` 排除集合，在目录遍历时 `continue` 跳过

### 新增（垃圾文件识别规则增强）
- 参考开源项目 [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) 的过滤器规则，新增 `SYSTEM_JUNK`（系统残留文件）分类
- **新增垃圾文件名精确匹配**（11 项）：`.DS_Store`、`desktop.ini`、`thumbs.db`、`.INSTALLATION`、`.imei.txt`、`bt_activity_pkt.txt.last`、`.mmsyscache`、`.tlocalcookieid`、`.wps_preloaded_2.txt`、`.oukdtft`、`ppy_cross`
- **新增垃圾文件名前缀匹配**（2 项）：`._`（macOS AppleDouble 文件）、`sdm_write_test-`（SD 卡写入测试文件）
- **新增垃圾目录名匹配**（8 项）：`LOST.DIR`、`.Trashes`、`.Trash`、`.spotlight`、`.Spotlight-V100`、`.fseventsd`、`.TemporaryItems`、`.Trash-1000`
- **新增广告 SDK 缓存目录前缀匹配**（15 项）：`ApplifierVideoCache`、`burstlyVideoCache`、`UnityAdsVideoCache`、`ApplifierImageCache`、`burstlyImageCache`、`UnityAdsImageCache`、`__chartboost`、`.chartboost`、`adhub`、`.mobvista`、`.goadsdk`、`.goproduct`、`.mologiq`、`.Adcenix`
- 垃圾目录和广告缓存目录的内容文件全部标记为 `SYSTEM_JUNK` 分类
- HomeScreen 新增 `SYSTEM_JUNK` 分类的图标（`Icons.Filled.BugReport`）

### 技术细节
- versionCode=9，versionName=2.7
- minSdk 31, targetSdk 36, compileSdk 36

---

## [v2.6] - 2026-08-02

### 新增（主题模式）
- 设置页新增「主题模式」选项，支持 3 种模式切换：
  - **日间模式**：始终使用浅色主题
  - **夜间模式**：始终使用深色主题
  - **跟随系统**：随系统深色模式自动切换（默认）
- 主题选择即时生效并持久化到 DataStore
- `ThemeMode` 枚举 + `AppSettings.themeMode` 字段 + `JingCunTheme` 参数化

### 修复（在线更新功能）
- **设置页更新卡片新增下载/安装按钮**：检测到新版本后直接在卡片内显示「下载更新」按钮和完整更新日志，不再仅依赖弹窗
- **修复启动时弹窗不弹出问题**：新增 `dialogDismissed` 标记，区分"关闭弹窗"和"清除状态"
  - 用户点击"稍后"只关闭弹窗，设置页仍保留下载按钮
  - 不再因 `dismissUpdate()` 清空所有状态导致无法再次下载
- **更新日志展示**：设置页更新卡片内显示可滚动的更新日志区域，弹窗内也展示完整更新说明
- 下载中状态在卡片内显示进度条，下载完成显示「立即安装」按钮
- 错误状态显示「重试」按钮

### 技术细节
- versionCode=8，versionName=2.6
- minSdk 31, targetSdk 36, compileSdk 36

---

## [v2.5] - 2026-08-02

### 修复（应用图标显示）

#### 应用占用空间排行图标修复
- **`AppStorageInfo` 新增 `icon: Drawable?` 字段**，扫描时通过 `PackageManager.getApplicationIcon()` 获取应用原始图标
- 标准通道（StorageStatsManager）与降级通道（sourceDir）均在创建 `AppStorageInfo` 时获取图标
- 获取图标使用 `runCatching` 容错，失败时为 null
- **UI 层 `AppStorageItemCard` 修复**：原硬编码 `Icons.Filled.Android` 通用图标，现渲染应用真实图标
- 新增 `drawableToImageBitmap()` 辅助函数：将任意 `Drawable`（含 `AdaptiveIconDrawable` 自适应图标）绘制到 Bitmap 再转 `ImageBitmap`，兼容所有图标类型
- 图标获取失败时回退到默认 Android 图标，保证健壮性

### 技术细节
- versionCode=7，versionName=2.5
- minSdk 31, targetSdk 36, compileSdk 36

---

## [v2.4] - 2026-08-02

### 修复（应用闪退问题）

#### 主题兼容性（关键修复）
- **`Theme.JingCunCleaner.Main` 的 parent 从 `android:Theme.DeviceDefault.Light.NoActionBar` 改为 `android:Theme.Material.Light.NoActionBar`**
- 原因：`Theme.DeviceDefault` 在部分定制 ROM（车机 DiLink 等）上可能被精简或缺失，导致 `Resources$NotFoundException` 启动即闪退
- `Theme.Material.Light.NoActionBar` 是所有 Android 5.0+ 设备 guaranteed 存在的系统主题

#### MainActivity 启动防御
- `enableEdgeToEdge()` 加 `runCatching` 包裹，防止部分定制 ROM 抛异常导致启动崩溃
- DataStore Flow 加 `.catch{}` 操作符，DataStore 文件损坏时返回默认设置而非崩溃
- `applyOrientation()` 加 `runCatching`，防止设置屏幕方向时异常

#### SettingsRepository DataStore 异常处理
- `settings` Flow 加 `.catch{}` 操作符，在 DataStore IO 异常时 emit `emptyPreferences()`（返回默认 AppSettings），避免 ViewModel `stateIn` 传播异常导致崩溃

#### CleanerViewModel init 块防御
- init 块所有操作（`refreshStorage`、`updatePermission`、`cleanExpired`、`checkForUpdate`）均加 `runCatching`
- 任一初始化步骤失败不再导致 ViewModel 创建失败 → Activity 闪退

### 技术细节
- versionCode=6，versionName=2.4
- minSdk 31, targetSdk 36, compileSdk 36

---

## [v2.3] - 2026-08-02

### 修复（鲁棒性 / 健壮性）

#### TrashManager 回收站
- 修复文件大小计算 bug：原实现先移动文件再计算大小，导致 size 恒为 0，现改为移动前计算
- 引入 `Mutex` 保证并发安全，避免多协程同时操作索引文件导致数据错乱
- 索引文件原子写入：先写 `index.json.tmp` 再 rename，防止崩溃导致索引损坏
- 错误处理：移动失败时正确返回 null，避免产生孤立索引项

#### UpdateChecker / UpdateInstaller OTA 更新
- 修复 `versionToLong` 版本比较：补齐到固定 4 段，解决 `2.2` 与 `2.2.0` 误判为不同版本
- 网络预检查：无网络时立即返回，不再等待 10s 超时
- HTTP 状态码区分：403 限速 / 404 仓库不存在给精确提示
- 响应体大小限制 1MB，防恶意超大 JSON 导致 OOM
- 下载协程取消处理：`ensureActive()` 响应用户取消，避免后台继续下载
- `HttpURLConnection` try/finally disconnect，防连接泄漏
- 下载缓冲区从 8KB 提升到 64KB，减少 I/O 开销
- 安装前检查 `canRequestPackageInstalls()`，未授权时返回特殊状态引导授权
- 修复 `InstallResult` sealed class 子类未调用构造函数的编译错误

#### ScanEngine 扫描引擎
- 修复空文件夹误判：`listFiles()` 返回 null 时跳过，避免无权限目录被当作空文件夹
- 修复 `isApkResidual` 空指针：`getPackageArchiveInfo` 返回 null 或 applicationInfo 为 null 时安全返回
- 符号链接处理：扫描时跳过 symlink，避免重复计算或死循环

#### CleanerViewModel 状态管理
- 扫描 / 清理协程包裹 try-catch，异常时回退 IDLE 状态而非卡死
- `CancellationException` 正确重新抛出，不吞掉协程取消信号
- `reset()` 时取消进行中的扫描 / 清理 Job，防止后台完成覆盖重置状态
- 修复 `java.io.File` 重复导入导致的编译错误

### 改进（UI 美化与体验）

#### HomeScreen 主页
- `StorageCard` 存储环改用 sweepGradient 渐变，圆环更有层次质感
- `IdleScanCard` 扫描入口图标容器使用 primary→tertiary 线性渐变背景
- `CleanedCard` 清理完成卡片：渐变圆形成功图标 + 大字号释放空间展示
- 圆角统一升级（24dp / 28dp），视觉更柔和现代
- 字体层级优化：标题加粗、副标题颜色层次分明

#### TrashScreen 回收站
- 顶部摘要卡升级：渐变圆形图标 + 大标题 + 紧凑操作按钮布局
- 空状态：渐变圆形图标容器 + 居中大字提示，视觉更友好
- 文件项卡片：文件类型图标容器统一为 secondaryContainer 圆形

#### AboutScreen 关于
- Hero 卡片：渐变圆形应用图标 + 版本徽章（半透明背景圆角胶囊）
- 信息卡片圆角与阴影统一

#### ApkOrganizerScreen APK 整理
- `ApkIntroCard` 图标改用渐变背景，与全局视觉风格一致
- `ApkDoneCard` 完成卡片：渐变圆形成功图标 + 圆角按钮

### 技术细节
- `minSdk` 31、`targetSdk` 36、`compileSdk` 36
- versionCode=5，versionName=2.3
- 构建工具：Gradle 8.14.4 + Kotlin 2.1.21 + Compose BOM

---

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
