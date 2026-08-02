# 发版流程

## 版本号规则

- `versionName`：`主版本.次版本`（如 `2.2`），用户可见
- `versionCode`：整数，单调递增（如 4），用于 App 内部比较
- Git tag：`v` + versionName（如 `v2.2`）

> ⚠️ `versionCode` 必须严格递增，否则旧版本无法检测到新版本（OTA 比较基于 versionName 点分解析，但 versionCode 仍是 Android 系统安装升级的依据）。

## 发版步骤

### 1. 改版本号

编辑 [app/build.gradle.kts](../app/build.gradle.kts)：

```kotlin
defaultConfig {
    applicationId = "com.jingcun.cleaner"
    minSdk = 31
    targetSdk = 36
    versionCode = 5        // ← 递增
    versionName = "2.3"    // ← 改为新版本
}
```

### 2. 更新更新日志

编辑 [CHANGELOG.md](../CHANGELOG.md)，在顶部新增：

```markdown
## [v2.3] - 2026-XX-XX

### 新增
- xxx

### 改进
- xxx

### 修复
- xxx
```

### 3. 构建 APK

```bash
cd JingCunCleaner
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

验证版本号：

```bash
/workspace/android-sdk/build-tools/36.0.0/aapt dump badging \
  app/build/outputs/apk/release/app-release.apk | grep version
```

应输出 `versionCode='5' versionName='2.3'`。

### 4. 提交代码

```bash
git add .
git commit -m "release: v2.3"
git push origin main
```

### 5. 创建 GitHub Release

#### 方式 A：用 gh CLI（推荐）

```bash
# 创建 Release
gh release create v2.3 \
  app/build/outputs/apk/release/app-release.apk \
  --title "v2.3" \
  --notes "## v2.3 更新内容

### 新增
- xxx"
```

#### 方式 B：用 curl API

```bash
# 1. 创建 Release
curl -X POST -H "Authorization: token $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/sreeny1/JingCunCleaner/releases \
  -d '{"tag_name":"v2.3","target_commitish":"main","name":"v2.3","body":"更新内容","draft":false,"prerelease":false}'

# 2. 从返回 JSON 拿到 release id 和 upload_url
# 3. 上传 APK 资产
curl -X POST -H "Authorization: token $GH_TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @app/build/outputs/apk/release/app-release.apk \
  "https://uploads.github.com/repos/sreeny1/JingCunCleaner/releases/<RELEASE_ID>/assets?name=jingcun-cleaner-v2.3.apk"
```

#### 方式 C：网页手动

1. 访问 https://github.com/sreeny1/JingCunCleaner/releases/new
2. Choose a tag → 输入 `v2.3` → Create new tag
3. Release title → `v2.3`
4. 描述更新内容
5. Attach binaries → 拖入 `app-release.apk`
6. 点 Publish release

### 6. 验证 OTA 链路

```bash
curl -s -H "Accept: application/vnd.github+json" \
  -H "User-Agent: JingCunCleaner-OTA" \
  https://api.github.com/repos/sreeny1/JingCunCleaner/releases/latest | python3 -m json.tool
```

应返回 `tag_name: v2.3` 且 `assets` 中包含新 APK。

### 7. 端到端测试

1. 在设备上安装旧版本 APK
2. 启动 App，等待几秒
3. 应自动弹出「发现新版本 v2.3」弹窗
4. 走完下载 → 安装流程
5. 验证安装后版本号为 v2.3

## 注意事项

### APK 命名

Release 资产命名建议：`jingcun-cleaner-v{版本号}.apk`（如 `jingcun-cleaner-v2.3.apk`），便于识别。

`UpdateChecker` 会取第一个 `.apk` 后缀的资产作为下载地址，命名不影响功能。

### Release 必须 public

仓库必须是 public，否则未授权的 OTA 检查 API 会返回 404。当前仓库 `sreeny1/JingCunCleaner` 已是 public。

### 不要删除旧 Release

旧版本用户启动 App 时会请求 `releases/latest`，删除最新 Release 会导致他们检测不到任何更新。如需撤回，改为 Draft 而非删除。

### Tag 与 versionName 必须一致

`UpdateChecker` 解析 `tag_name` 去掉 `v` 前缀后与 App 的 `versionName` 比较。如果 tag 是 `v2.3` 但 versionName 是 `2.2`，会无限循环提示更新。确保两者一致。

### versionCode 递增

虽然 OTA 比较基于 versionName，但 Android 系统安装升级基于 versionCode。新版本 versionCode 必须 > 旧版本，否则系统会拒绝降级安装。

## 回滚

如果新版本有严重 bug：

1. 在 GitHub 把有问题的 Release 改为 Draft（隐藏它）
2. 重新发布旧版本（如把 v2.2 重新作为 latest）
3. 受影响用户启动 App 时会检测到「旧版本」并提示降级（但系统不允许降级安装，需先卸载）

更好的做法：发布修复版本（如 v2.3.1），而不是回滚。

## 紧急修复流程

1. 改 versionName 到 `2.2.1`，versionCode 递增到 5
2. 只改 bug，不加新功能
3. 构建 + 发 Release（tag `v2.2.1`）
4. 用户自动检测到并更新
