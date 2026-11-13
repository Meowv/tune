# 仓库协作指南

MeowvTune 是使用 Kotlin 与 Jetpack Compose 开发的单模块 Android 应用，面向手机和车机，`minSdk` 为 28，`compileSdk`/`targetSdk` 为 35。

## 项目结构

- `app/src/main/java/com/meowv/tune/`：生产代码。`ui` 负责 Compose 界面，`domain` 存放共享模型与契约，`radio`、`playback`、`network`、`settings`、`update` 分别承担对应领域逻辑。
- `app/src/main/res/`：颜色、尺寸、图标、字符串及其他 Android 资源。
- `app/src/test/`：JUnit 4 JVM 测试；`app/src/androidTest/`：设备测试、Compose UI 测试及媒体测试资源。
- `build.sh`、`run.sh`：APK 构建与模拟器安装入口。`docs/` 是被 Git 忽略的本地截图和诊断资料。

## 环境与命令

需要 JDK 17 和包含 API 35 的 Android SDK。

| 目的           | 命令                                  |
| -------------- | ------------------------------------- |
| 编译 Debug APK | `./gradlew :app:assembleDebug`        |
| 运行 JVM 测试  | `./gradlew test`                      |
| 运行设备测试   | `./gradlew connectedDebugAndroidTest` |
| 静态检查       | `./gradlew lint`                      |
| 构建并定位 APK | `./build.sh debug`                    |
| 安装并运行应用 | `./run.sh debug`                      |
| 检查车机布局   | `./run.sh --car`                      |

## 开发约定

- 修改前确认相关调用链和现有行为；采用最小、完整的改动，保留无关的本地修改。
- 遵循 `kotlin.code.style=official`：四空格缩进、分组导入；类型与 Compose 函数使用 `PascalCase`，函数和属性使用 lowerCamelCase，包名使用小写。
- 复用现有 Compose 组件、工具函数和依赖。依赖版本统一在 `gradle/libs.versions.toml` 中声明，并在模块构建文件中引用。
- 仅对不明显的设计添加中文注释；复杂逻辑优先拆分为语义明确的函数或接口，避免用长注释掩盖结构问题。
- 可见文案放入 `strings.xml`，尺寸和间距优先复用现有资源，确保手机与车机布局均可用。
- 更新日志直接展示清单原文，保留空白、换行和 🆕 等装饰，不增加清理、重写或关键词分类逻辑；保留前 5 条摘要及展开交互。

## 测试要求

- JVM 测试使用 JUnit 4；仪器测试使用 AndroidX 和 Compose UI Test。
- 测试类和文件命名为 `<被测对象>Test`，方法使用描述行为的 lowerCamelCase，例如 `offlineBlocksPlayback()`。
- 优先使用 Fake 和 `MockWebServer`，避免测试依赖真实电台服务。项目未设置覆盖率门槛，但新增行为应有针对性测试。
- 逻辑变化至少执行相关 JVM 测试；涉及界面、媒体或生命周期时验证设备行为，并检查手机与车机布局。
- 设备测试默认针对 Debug；修改 R8、资源裁剪或打包配置时，另行验证实际 Release 的安装、启动、音频播放、通知和系统媒体控制，并区分模拟器与真机验证结果。

```bash
./gradlew :app:testDebugUnitTest --tests "com.meowv.tune.radio.DirectoryCacheTest"
```

## 发布与安全

- 正式构建使用 `./build.sh release --bump`。`versionName` 在 `app/build.gradle.kts` 中手动维护，`versionCode` 由 `--bump` 递增，脚本失败时回滚。
- 仅验证 Release 构建时执行 `./gradlew :app:assembleRelease`，产物位于 `app/build/outputs/apk/release/`，不递增版本码或生成发布归档。
- Release 使用 R8、资源裁剪与压缩 DEX；Media3 复用依赖自带的 consumer 规则。图片使用无损 WebP，调整编码时验证尺寸和 RGBA 像素一致。
- 发布产物位于 `app/build/meowvtune/`。`build.sh release` 会替换该目录，并在 `STUDIO_DATA_DIR` 存在时同步替换其 `meowvtune/` 子目录；不自动部署远端。更新清单地址和渠道由 `UPDATE_MANIFEST_URL`、`UPDATE_CHANNEL` 配置。
- `local.properties`、`keystore.properties`、`*.jks` 和 `*.keystore` 仅属于本机环境，必须保持未跟踪。
- 不得在源码、测试、命令、日志或回复中输出签名口令及其他凭据。
