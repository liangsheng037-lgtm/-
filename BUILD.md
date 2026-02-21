# Android APK 构建

## 本地构建

在 `android/` 目录执行：

- Debug APK：`./gradlew :app:assembleDebug`
- Release APK（未签名或按配置签名）：`./gradlew :app:assembleRelease`

可选参数（也可以写到 `~/.gradle/gradle.properties`）：

- `-PAPK_ID=<后端分配的 apkId>`
- `-PAGENT_CODE=<渠道码>`
- `-PAPPLICATION_ID=<自定义包名>`

签名（可选）：

- `-PKEYSTORE_FILE=<keystore 路径>`
- `-PKEYSTORE_PASSWORD=<store 密码>`
- `-PKEY_ALIAS=<key alias>`
- `-PKEY_PASSWORD=<key 密码>`

## CI 自动构建（GitHub Actions）

工作流文件：`.github/workflows/android-apk.yml`

- 默认构建 Debug + Release APK，并作为产物上传。
- 如果未配置签名，Release 产物可能是未签名 APK（仍可用于测试/分发到支持未签名的渠道）。

### 推荐用法：按 APK 配置 ID 触发构建

后端的 “APK 管理（apk_configs）” 里，`id` 就是端侧识别用的唯一标识（apkId）。构建时把这个值写入 APK：

- Android 工程会把 `APK_ID` 写进 `BuildConfig.APK_ID`
- App 启动后会优先使用 `BuildConfig.APK_ID` 作为 apkId 上报/拉取配置

因此建议流程是：

1. 在后端创建一条 `apk_configs`，生成/填写 `id`（例如 `apk_xxx`）
2. 在 GitHub Actions 手动触发 `Build Android APK`，输入 `apk_id=<id>`
3. 下载构建产物 APK，分发安装

### workflow_dispatch 输入

在 GitHub Actions 的手动触发界面可输入：

- `apk_id`：写入 APK 的唯一标识（对应后端 `apk_configs.id`）
- `agent_code`：渠道码（可选）
- `application_id`：包名覆盖（可选）

### 可选：启用 Release 签名

在仓库的 Secrets 中配置：

- `ANDROID_KEYSTORE_B64`：keystore 文件的 base64 内容
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

在仓库的 Variables（非 Secrets）中可选配置：

- `APK_ID`
- `AGENT_CODE`
