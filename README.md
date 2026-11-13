<div align="center">
  <img src="./app/src/main/res/drawable-xxhdpi/ic_meowvtune_logo.webp" alt="Meowv Tune Logo" width="112" />

  <h1>喵听听 · Meowv Tune</h1>

  <p><strong>音乐不断，喵趣随行。</strong></p>
  <p>为 Android 手机与车机打造的轻量级网络电台应用。<br />打开就听，简单纯粹，随时发现世界各地的声音。</p>

  <p>
    <img src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 9.0+" />
    <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
    <img src="https://img.shields.io/badge/Media3-ExoPlayer-6750A4" alt="Media3 ExoPlayer" />
    <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT License" />
  </p>

  <p>
    <a href="#-功能亮点">功能亮点</a> ·
    <a href="#-当前进度">当前进度</a> ·
    <a href="#-快速开始">快速开始</a> ·
    <a href="#-技术栈">技术栈</a>
  </p>
</div>

---

## ✨ 项目介绍

**Meowv Tune（喵听听）** 是一款使用 **Kotlin + Jetpack Compose** 开发的原生 Android 音频应用，专注于轻松、流畅的电台收听体验。

同一套应用界面可根据窗口尺寸适配手机与横屏大屏设备，包括支持安装普通 Android APK 的车机。没有繁杂的社交、收藏或推荐信息流，专注于「找到电台，然后好好听」。

> **当前版本以网络电台为核心。** 音乐菜单已预留，但音乐功能仍在开发中。

## 🌟 功能亮点

### 📻 发现电台

- **全球电台目录**：基于 [Radio Browser](https://www.radio-browser.info/) 社区电台数据库。
- **按地区收听**：根据网络国家信息和系统区域自动选择地区，也支持手动切换或使用「不限区域」。
- **双视图浏览**：列表模式快速查找，卡片模式专注于单个电台；支持手势切换。
- **顺序 / 随机切台**：在当前范围内切换电台，也可以探索全球电台。
- **目录缓存与刷新**：分页加载、缓存复用、手动刷新以及异常重试。

### 🎧 沉浸式播放

- **圆形播放入口 + 全屏播放页**：手机位于底部菜单左侧，车机位于侧栏底部；点击进入全屏播放页。
- **封面旋转与状态波形**：播放时封面旋转，暂停时保持角度；电台列表与卡片通过波形同步展示播放、暂停和加载状态。
- **手势切换电台**：无需反复返回列表。
- **睡眠定时**：支持预设时长与自定义分钟数，到时自动停止播放。
- **后台播放与系统媒体控制**：使用 Media3、ExoPlayer 和 MediaSession，支持系统媒体通知及外部媒体控制器。
- **播放可靠性处理**：针对网络变化、连接超时、缓冲中断和音源异常提供状态反馈及恢复策略。

### 🚗 手机与车机自适应

- 手机以紧凑布局和底部导航为主，菜单使用圆角选中背景，支持点击与横滑切页。
- 横屏大屏采用侧边导航与更宽敞的内容布局。
- 页面与播放器会根据可用窗口尺寸调整布局；设置页限制内容宽度，版本与更新页在足够宽的窗口中采用双列卡片。
- 能否使用方向盘按键等硬件控制，取决于车机系统对 Android 媒体会话的兼容情况。

### ⚙️ 设置与版本更新

- 启动自动播放可开关；关闭后仍可恢复上次电台的展示信息，不会自动起播。
- 持久化保存收听范围、浏览模式及播放顺序等偏好。
- 三组设置卡片分别包含自动播放、刷新与清理缓存、数据来源与开源许可及版本更新入口，不增加分组标题。
- 刷新期间提供加载反馈并阻止重复触发；刷新与清理缓存完成后显示操作结果。
- 检查自托管版本清单，展示真实检查状态、当前版本、构建版本与更新渠道；未检查或检查失败时不会显示“已是最新版本”。
- 更新日志按清单原文展示，保留 🆕 等装饰，默认显示前 5 条，支持展开与收起；窗口布局切换时保留展开状态。
- 下载 APK 后校验文件大小、SHA-256、包名和签名，再交由 Android 系统安装器完成安装；取消安装后保留已校验的包，可以再次安装。

## 🚧 当前进度

| 模块     | 状态      | 说明                                   |
| -------- | --------- | -------------------------------------- |
| 电台     | ✅ 已实现 | 地区选择、列表 / 卡片、顺序 / 随机切换 |
| 播放器   | ✅ 已实现 | 后台播放、圆形播放入口、沉浸式播放页     |
| 睡眠定时 | ✅ 已实现 | 预设时长、自定义时长                   |
| 设置     | ✅ 已实现 | 三组卡片、自动播放、缓存管理、偏好持久化         |
| 版本更新 | ✅ 已实现 | 真实状态、日志展开、下载校验与系统安装             |
| 音乐     | 🚧 开发中 | 当前为占位页面，暂不提供音乐播放功能   |

功能与实际体验以当前代码和所安装 APK 为准。

## 🧱 技术栈

| 分类       | 技术                                                  |
| ---------- | ----------------------------------------------------- |
| 语言与构建 | Kotlin、Gradle Kotlin DSL、JDK 17                     |
| UI         | Jetpack Compose、Material 3                           |
| 音频播放   | AndroidX Media3、ExoPlayer、MediaSession              |
| 网络与数据 | OkHttp、Kotlinx Serialization、dnsjava                |
| 图片加载   | Coil 3                                                |
| 本地配置   | Preferences DataStore                                 |
| 异步与状态 | Kotlin Coroutines、Flow                               |
| 数据来源   | [Radio Browser API](https://docs.radio-browser.info/) |

当前构建配置：**minSdk 28（Android 9.0）**、**targetSdk 35**、**compileSdk 35**。

### 代码结构

```text
app/src/main/java/com/meowv/tune/
├── domain/       # 领域模型与接口
├── network/      # Radio Browser API、镜像发现
├── radio/        # 电台目录、区域解析、队列及状态
├── playback/     # 播放服务、MediaSession、重试、睡眠定时
├── settings/     # DataStore 配置与设置状态
├── ui/           # 电台、音乐、设置、沉浸式播放、更新页面
│   └── theme/    # 主题与设计规范
└── update/       # 版本检查、下载、校验与安装
    └── install/
```

项目同时包含单元测试（`app/src/test`）和 Android 设备测试（`app/src/androidTest`）。

## 🚀 快速开始

### 环境要求

- Android Studio（支持本项目使用的 Android Gradle Plugin）
- JDK 17
- Android SDK Platform 35、Build Tools 34.0.0
- 一台运行 Android 9.0 或更高版本的设备，或 Android 模拟器

### 克隆与构建

```bash
git clone https://github.com/Meowv/tune.git
cd tune

# macOS / Linux：构建 Debug APK
./gradlew :app:assembleDebug

# Windows：
# gradlew.bat :app:assembleDebug
```

Debug 安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

也可以使用仓库提供的辅助脚本：

```bash
./build.sh debug        # 构建 Debug APK
./build.sh release      # 构建 Release APK
./run.sh --list         # 查看可用 Android 模拟器
./run.sh                # 启动模拟器并安装 Debug 包
./run.sh --car          # 使用横屏大屏参数模拟车机界面
```

`build.sh` 和 `run.sh` 使用 Bash；`run.sh` 的默认 SDK 路径面向 macOS，其他环境请按脚本帮助和本机配置调整。完整参数可通过 `./build.sh --help` 与 `./run.sh --help` 查看。

### 安装到设备

构建后使用 Android Studio 安装，或者在设备开启开发者选项、USB 调试并连接 ADB 后执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 📦 Release 构建与更新发布

项目支持自托管 APK 更新，默认稳定版清单地址配置在 `app/build.gradle.kts` 中：

```text
https://studio.meowv.com/data/meowvtune/stable.json
```

正式发布前，在仓库根目录准备**仅保存在本机**的 `keystore.properties`，并配置：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

然后执行：

```bash
# 仅构建 Release APK，不生成发布归档或同步到其他项目
./gradlew :app:assembleRelease

# 构建并生成本地发布产物
./build.sh release

# 正式发版时可选择递增 versionCode
./build.sh release --bump
```

- `versionName` 需要在 `app/build.gradle.kts` 中手动维护；仅 `--bump` 递增 `versionCode`，脚本失败时会回滚该版本码。
- 直接执行 Gradle 时，APK 位于 `app/build/outputs/apk/release/`；不会更新 `app/build/meowvtune/` 中已有的发布归档。
- 构建脚本会在有签名且可读取 APK 元数据时生成发布目录 `app/build/meowvtune/`，包含 APK 与 `stable.json`。
- 可在根目录创建 `release-notes.txt`，每行一条更新说明，写入版本清单。
- `build.sh release` 会替换本地发布目录中的旧产物；如果 `STUDIO_DATA_DIR` 存在，还会替换其 `meowvtune/` 子目录并同步 APK 与清单，默认路径为 `../meowv.com/build/studio.meowv.com/data`。
- 本地生成与同步不会自动部署到远端；需要将发布产物部署到自己维护的 HTTPS 服务，确保清单中的 APK 下载地址可访问。
- **请勿提交 keystore、密码或签名配置。** 仓库的 `.gitignore` 已忽略 `keystore.properties`。
- 未配置签名时仍可构建未签名 Release APK，但不能将其作为可供用户升级的正式安装包。

更新采用用户确认的系统安装流程，不会静默安装 APK。

### 安装包体积优化

Release 已启用 R8、资源裁剪和 DEX 压缩。Media3 的反射入口使用依赖自带的 consumer 规则，避免整包保留；Logo、启动图及通知图标采用无损 WebP，保持原有尺寸与像素。Debug 保持原有 DEX 打包方式，体积对比应使用 Release APK。

压缩主要降低 APK 下载体积，安装后的磁盘占用和启动性能需要单独测量。

### Gradle 缓存锁异常

如果出现 `Cannot lock file hash cache ... as it has already been locked by this process`，确认没有其他构建运行后，停止旧守护进程，再重新构建：

```bash
./gradlew --stop
./gradlew :app:assembleRelease
```

该命令不递增版本码；需要生成新版本发布产物时，再执行 `./build.sh release --bump`。

## 🧪 测试

```bash
# JVM 单元测试
./gradlew :app:testDebugUnitTest

# Android Lint
./gradlew :app:lintDebug

# 连接设备或启动模拟器后执行设备测试
./gradlew :app:connectedDebugAndroidTest
```

上述设备测试默认针对 Debug。调整 R8、资源裁剪或打包配置后，还需验证实际 Release 的安装、启动、播放和系统媒体控制；Debug 测试通过不能替代 Release 或真机验收。

## 💡 设计理念

**少一点打扰，多一点聆听。**

喵听听希望保留电台本来的魅力：打开应用，挑选一个频道，让声音陪伴通勤、工作与日常生活。

不需要账号，不依赖个性化推荐，也不提供复杂的社交体系。电台目录及台标来自第三方社区数据，实际音频由各电台服务提供，内容和可用性取决于对应的电台。

## 🤝 反馈与贡献

欢迎通过 [Issues](https://github.com/Meowv/tune) 反馈问题、提出建议，也欢迎提交 Pull Request。

第三方电台内容、台标及相关商标由各自权利人所有。

## 📄 许可证

本项目基于 [MIT License](./LICENSE) 开源，Copyright (c) 2026 阿星Plus。

---

<div align="center">
  <strong>喵听听 · Meowv Tune</strong><br />
  好歌不断，喵趣随行。<br /><br />
  Made with ❤️ by <a href="https://github.com/Meowv">Meowv</a>
</div>
