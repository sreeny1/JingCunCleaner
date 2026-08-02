# 高刷适配说明

## 设计目标

- 默认 60Hz（与系统默认行为一致，不额外耗电）
- 系统支持更高刷新率（90/120/144Hz）时自动跟随最高帧率
- 不改变分辨率（避免画面变形或 DPI 异常）
- 无需 manifest 声明额外权限

## 实现位置

[MainActivity.kt](../app/src/main/java/com/jingcun/cleaner/MainActivity.kt) 的 `applyHighRefreshRate()` 方法，在 `onCreate()` 中调用。

## 核心代码

```kotlin
private fun applyHighRefreshRate() {
    runCatching {
        val display = display ?: return@runCatching
        val currentMode = display.mode
        val modes = display.supportedModes
        if (modes.isNullOrEmpty()) return@runCatching
        // 仅在当前分辨率相同的 mode 中挑选，防止分辨率被切换
        val sameResModes = modes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        val candidates = if (sameResModes.isNotEmpty()) sameResModes else modes.toList()
        val bestMode = candidates.maxByOrNull { it.refreshRate } ?: return@runCatching
        // 仅当系统最高刷新率高于 60Hz 时才主动设置
        if (bestMode.refreshRate <= 60f) return@runCatching
        val attrs = window.attributes
        attrs.preferredDisplayModeId = bestMode.modeId
        window.attributes = attrs
    }
}
```

## 原理说明

### Display.Mode

Android 6.0（API 23）+ 引入 `Display.Mode`，描述「分辨率 + 刷新率」组合。一个屏幕可能支持多种 mode，例如：

| modeId | physicalWidth | physicalHeight | refreshRate |
| --- | --- | --- | --- |
| 1 | 1080 | 2400 | 60.0 Hz |
| 2 | 1080 | 2400 | 90.0 Hz |
| 3 | 1080 | 2400 | 120.0 Hz |
| 4 | 720 | 1600 | 144.0 Hz |

注意：mode 4 是低分辨率高刷新率，直接选它会改变分辨率。

### preferredDisplayModeId

`Window.attributes.preferredDisplayModeId` 让 App 指定想要的 mode。系统会尽量满足（不保证一定生效，例如省电模式下可能强制 60Hz）。

### 为什么先按分辨率过滤

如果直接 `modes.maxByOrNull { it.refreshRate }`，可能选到低分辨率高刷新率的 mode（如上表 mode 4），导致画面变形。所以先过滤出「与当前分辨率相同的 mode」，再从中选最高刷新率。

降级策略：如果过滤后为空（极端情况），回退到所有 mode 中选最高刷新率。

### 为什么判断 > 60f

60Hz 设备的 `supportedModes` 里可能只有 60Hz 的 mode，或者最高就是 60Hz。这种情况不主动设置 `preferredDisplayModeId`，保持系统默认行为，避免无谓的 window 属性更新。

## 适配的设备类型

| 设备类型 | 支持刷新率 | App 行为 |
| --- | --- | --- |
| 普通手机（60Hz） | 60Hz | 保持默认，不主动设置 |
| 中高端手机（90/120Hz） | 60/90/120Hz | 自动跟随最高（120Hz） |
| 旗舰手机（144Hz） | 60/90/120/144Hz | 自动跟随最高（144Hz） |
| 车机（60Hz） | 60Hz | 保持默认 |
| 平板（120Hz） | 60/120Hz | 自动跟随最高（120Hz） |

## 常见坑

### 1. `windowManager.defaultDisplay` 已废弃

Android 11+ 推荐 `Activity.display` 属性获取 Display 对象。本项目用 `display`（API 30+，minSdk=31 安全）。

### 2. `supportedModes` 可能为空

某些模拟器或非常规设备返回空数组。代码用 `isNullOrEmpty()` 判空并 return。

### 3. 系统省电模式可能强制 60Hz

即使设置了 `preferredDisplayModeId`，系统在省电模式下可能强制降到 60Hz。这是系统行为，App 无法绕过，也无需处理。

### 4. `preferredDisplayModeId` 是 per-window 的

只影响当前 Window，不影响其他 App 或系统。Activity 重建时需要重新设置（本项目在 `onCreate()` 每次都调用，符合要求）。

## 测试方法

### 查看设备支持的 mode

```bash
adb shell dumpsys display | grep -A 20 "DisplayDeviceInfo"
```

或用 App 内日志（可自行加 `Log.d`）：

```kotlin
modes.forEach { Log.d("RefreshRate", "mode=${it.modeId} ${it.physicalWidth}x${it.physicalHeight} @${it.refreshRate}Hz") }
```

### 验证当前刷新率

```bash
adb shell dumpsys SurfaceFlinger --display
```

或在 App 运行时查看 `display.mode.refreshRate`。

## 不采用的方案

### 方案 A：manifest meta-data

```xml
<meta-data android:name="android.high_refresh_rate" android:value="true" />
```

不存在这个官方 meta-data，属于伪科学。

### 方案 B：强制 120Hz

直接写死 `preferredDisplayModeId = 某个120Hz的modeId`。问题：
- 不同设备 modeId 不同，硬编码会崩
- 144Hz 设备会被限制到 120Hz

### 方案 C：用 `Settings.Global` 修改系统设置

需要 `WRITE_SECURE_SETTINGS` 权限，非系统应用拿不到。且影响全局，不符合 App 应只管自己的原则。

## 结论

当前实现（按分辨率过滤 + 选最高 + >60Hz 才设置）是平衡了兼容性、安全性、效果的方案，适配 60/90/120/144Hz 全系设备，且不影响 60Hz 设备的功耗。
