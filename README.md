<div align="center">
  <img src="./app/src/main/res/drawable-xxhdpi/ic_meowvtune_logo.webp" alt="喵听听 Logo" width="112" />

  <h1>喵听听 · Meowv Tune</h1>

  <p><strong>好歌不断，喵趣随行。</strong></p>
  <p>为 Android 手机与车机打造的原生电台与音乐播放器。<br />听遍全球电台，发现喜欢的音乐，让好声音一直陪伴。</p>

  <p>
    <img src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 9.0+" />
    <img src="https://img.shields.io/badge/Kotlin-2.1.20-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.1.20" />
    <img src="https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
    <a href="https://github.com/Meowv/tune/actions/workflows/android.yml"><img src="https://github.com/Meowv/tune/actions/workflows/android.yml/badge.svg?branch=main" alt="Android checks" /></a>
    <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-F5A623" alt="MIT License" /></a>
  </p>

  <p>
    <a href="#-功能一览">功能一览</a> ·
    <a href="#-界面与设备适配">设备适配</a> ·
    <a href="#-音乐服务与设备激活">音乐服务</a> ·
    <a href="#-快速开始">安装与构建</a> ·
    <a href="#-release-构建与更新发布">版本发布</a>
  </p>
</div>

---

## ✨ 项目介绍

**喵听听（Meowv Tune）** 是一款基于 **Kotlin + Jetpack Compose + Media3** 的原生 Android 音频应用，面向手机与可安装 Android APK 的车机，围绕 **电台、音乐、设置** 三个入口提供简洁、连续的收听体验。

**电台和音乐是喵听听的两大核心模块。** 电台可直接浏览并收听；音乐支持推荐、搜索、播放与歌词，但依赖配套音乐 API 和管理员设备激活。**“需要激活”是访问控制，不代表音乐功能仍在开发中。**

### ✅ 功能一览

| 模块 | 已实现能力 |
| --- | --- |
| 📻 电台 | 全球电台、地区切换、列表 / 卡片视图、顺序 / 随机切换、缓存与刷新 |
| 🎵 音乐 | 连续推荐、歌曲搜索、双视图浏览、播放队列、歌词、进度拖动、续播 |
| 🎧 播放器 | 迷你播放条、沉浸式播放页、后台播放、媒体会话、睡眠定时 |
| 📱 设备适配 | 手机底部导航、车机侧边导航、根据窗口尺寸响应式布局 |
| 🎨 外观与设置 | 浅色 / 深色 / 跟随系统、自动播放、偏好保存、缓存管理 |
| 📦 版本更新 | 自托管更新清单、更新日志、APK 下载校验与系统安装 |

### 📻 电台 · 收听世界各地的声音

- **全球电台**：使用 [Radio Browser](https://www.radio-browser.info/) 社区维护的电台目录。
- **智能地区选择**：综合网络国家与系统区域选择默认收听范围，也可手动切换国家 / 地区或使用「不限区域」。
- **列表与卡片双视图**：列表快速浏览，卡片一次聚焦一个电台，支持滑动切换。
- **顺序与随机切台**：按当前地区或全球范围发现更多电台。
- **缓存与容错**：支持分页加载、目录缓存、手动刷新、网络失败提示及重试。

### 🎵 音乐 · 从一首歌接着听下去

- **连续音乐推荐**：展示推荐歌曲，自动补充后续播放队列，并根据本机收听记录调整候选顺序。
- **搜索与历史**：按歌名或歌手搜索，支持分页结果和最近 **10 条**本地搜索记录。
- **列表与卡片双视图**：可在高密度列表与单曲卡片间切换，沿用一致的浏览体验。
- **完整播放控制**：支持播放 / 暂停、上一曲 / 下一曲、可拖动进度条、播放位置恢复。
- **歌词展示**：沉浸式播放页显示随播放进度更新的歌词预览（以音源实际返回的数据为准）。
- **连续播放可靠性**：队列去重、候选补充、音源不可用时的备选解析与错误反馈。

音乐目录、封面、歌词及可播放地址由配套服务提供；**实际可播放曲目取决于音源可用性及对应授权**。

### 🎧 播放体验

- **轻量迷你播放条**：在电台、音乐及设置页面之间切换时保持播放入口。
- **沉浸式播放页**：电台展示台标与播放状态；音乐展示封面、歌曲信息、歌词及进度。
- **后台与媒体控制**：基于 Media3 / MediaSession，支持系统通知及兼容的蓝牙、媒体按键控制。
- **睡眠定时**：15 / 30 / 45 / 60 分钟快捷选项，亦可自定义 1–480 分钟。
- **网络与音频中断处理**：针对缓冲、连接失败、网络切换、音频焦点与设备断开提供状态管理与恢复策略。

### 🚗 界面与设备适配

| 场景 | 界面与交互 |
| --- | --- |
| 手机竖屏 | 底部三个一级菜单，单栏内容，底部面板与迷你播放条 |
| 车机 / 横屏大屏 | 左侧导航 + 主内容区，按宽度使用单列 / 双列布局，保留系统状态栏 |
| 两端共用 | 电台、音乐、搜索、沉浸式播放、设置与版本更新，自动适应可用窗口尺寸 |

支持 **Android 9.0（API 28）及以上**。车机无需单独维护另一套 APK，但实际横竖屏表现由设备窗口与系统配置决定。方向盘按键、系统媒体面板及 HUD 是否能显示播放信息，取决于车机厂商对 Android 媒体会话的支持；**不将其视为所有车型均支持的能力**。

### ⚙️ 设置与版本更新

- **主题**：浅色、深色、跟随系统，选择会保存并在后续启动中生效。
- **播放偏好**：启动自动播放、地区、视图模式与播放顺序持久化。
- **数据管理**：手动刷新目录、清理缓存、查看数据来源和开源许可。
- **设备状态**：显示音乐设备激活状态、设备码及必要的公钥指纹，便于管理员核对。
- **应用更新**：检查自托管的稳定版清单、查看更新日志、下载 APK，校验大小、SHA-256、包名、版本码及系统兼容性，再交给 Android 系统安装器确认安装；不执行静默安装。

### 🔑 音乐服务与设备激活

**电台无需账号即可收听；音乐使用配套 API，须完成设备激活。**

1. 启动 App，在「设置 → 音乐设备」查看当前状态与设备码。
2. 管理员在音乐服务的 `/setup` 管理页面核对**设备码和公钥指纹**后批准设备。
3. App 在前台自动领取访问凭据；如需使用 QQ 会员相关能力，由管理员**单独授权**。
4. 设备重装或清理 App 数据后的授权恢复、重新审批及撤销状态由设备身份和服务端审批策略共同控制。

音乐服务需要自行部署或使用已配置的服务地址。默认 **Debug** 指向模拟器宿主机的 `http://10.0.2.2:5000/api/music/`，**Release** 使用 HTTPS。客户端不会在仓库中保存音乐会员 Cookie、服务端凭据或签名私钥。

## 🧱 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言与构建 | Kotlin 2.1.20、Gradle Kotlin DSL、JDK 17 |
| UI | Jetpack Compose、Material 3 |
| 音频播放 | AndroidX Media3、ExoPlayer、MediaSession |
| 网络与数据 | OkHttp、Kotlinx Serialization、dnsjava |
| 图片加载 | Coil 3 |
| 本地配置 | Preferences DataStore |
| 异步与状态 | Kotlin Coroutines、Flow |
| 电台数据来源 | [Radio Browser API](https://docs.radio-browser.info/) |

构建配置：**minSdk 28（Android 9.0）**、**targetSdk 35**、**compileSdk 35**。发布版本与更新日志以 App「设置 → 版本与更新」及服务端版本清单为准。

### 代码结构

```text
app/src/main/java/com/meowv/tune/
├── domain/       # 领域模型与接口
├── music/        # 设备激活、音乐推荐、搜索、历史及续播
├── network/      # Radio Browser API、镜像发现
├── radio/        # 电台目录、区域解析、队列及状态
├── playback/     # 播放服务、MediaSession、重试、睡眠定时
├── settings/     # 本地配置、主题与设置状态
├── ui/           # 电台、音乐、设置、沉浸式播放、更新页面
│   └── theme/    # 主题与设计规范
└── update/       # 版本检查、下载、校验与安装
    └── install/
```

项目还包含 JVM 单元测试（`app/src/test`）、Android 设备与 Compose UI 测试（`app/src/androidTest`）、GitHub Actions 校验流水线（`.github/workflows/android.yml`）。

## 🚀 快速开始

### 安装与使用

- **已有安装版本**：在 App 中打开「设置 → 版本与更新」检查更新，按提示下载并确认安装。
- **首次安装**：使用项目发布者提供的**正式签名 APK** 安装到 Android 9.0+ 手机或车机。仓库中的源码构建包与正式分发包需区分签名。
- **电台**：启动即可选择地区并收听，具体电台是否可播取决于第三方直播源。
- **音乐**：在「设置 → 音乐设备」完成管理员激活后，即可使用音乐推荐、搜索及播放。

> 更新清单配置于 `app/build.gradle.kts`，默认指向 [stable.json](https://studio.meowv.com/data/meowvtune/stable.json)；该文件用于**已安装 App 的版本检查**，不是 GitHub Releases 下载页。

### 开发环境

- Android Studio（兼容 Android Gradle Plugin 8.7.2）
- JDK 17
- Android SDK Platform 35 + Build Tools 34.0.0
- Android 9.0+ 真机或模拟器

### 克隆与构建

```bash
git clone https://github.com/Meowv/tune.git
cd tune

# macOS / Linux
./gradlew :app:assembleDebug

# Windows
gradlew.bat :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

也可使用项目脚本：

```bash
./build.sh debug      # 生成 Debug APK
./build.sh release    # 构建本地 Release 发布产物
./run.sh --list       # 列出可用模拟器
./run.sh              # 启动模拟器并安装 Debug
./run.sh --car        # 使用横屏大屏参数查看车机布局
```

脚本依赖 Bash；`run.sh` 的默认 SDK 路径面向 macOS，其他系统需根据本机环境调整。运行 `./build.sh --help`、`./run.sh --help` 查看完整参数。

### 安装 Debug APK

启用设备的开发者选项及 USB 调试，连接 ADB 后执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 配置音乐 API（开发者）

仓库仅包含 Android 客户端。音乐模块还需要可用的服务端 API 及设备审批流程。可在仓库根目录创建**仅本机保存**的 `music-service.properties`：

```properties
MUSIC_DEBUG_API_BASE_URL=http://10.0.2.2:5000/api/
MUSIC_RELEASE_API_BASE_URL=https://your-domain.example/api/
```

也可设置同名环境变量。构建过程会自动补全 `/api/music/` 路径；Release 必须使用 HTTPS，Debug 明文地址仅允许本机或内网。请勿提交该配置文件、签名信息、设备访问令牌或第三方会员凭据。

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
- 构建脚本会在有签名且可读取 APK 元数据时生成发布目录 `app/build/meowvtune/`，包含 APK 与 `stable.json`。更新日志保留原有缩进和空行。
- 可在根目录创建 `release-notes.txt`，每行一条更新说明，写入版本清单。
- `build.sh release` 会先准备并校验本地发布副本。如果 `STUDIO_DATA_DIR` 存在，也会准备其 `meowvtune/` 副本。所有副本校验通过后才替换目录，替换失败时尝试恢复旧产物。Studio 默认路径为 `../meowv.com/build/studio.meowv.com/data`。
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

GitHub Actions 会在 push 和 Pull Request 时执行测试、Lint 与 Debug 编译。Android 模拟器的部分设备测试可以通过 workflow_dispatch 手动触发。

设备测试默认针对 Debug。调整 R8、资源裁剪或打包配置后，还需验证实际 Release 的安装、启动、播放和系统媒体控制；Debug 测试通过不能替代 Release 或真机验收。

## 💡 设计理念

**少一点打扰，多一点聆听。**

不追求复杂的社交系统或大量功能入口，而是专注于发现一首喜欢的歌、收听一个电台，以及在手机和车机之间保持一致的使用体验。

### 数据来源与使用说明

- **电台**：电台目录和台标来自 [Radio Browser](https://www.radio-browser.info/) 社区数据；音频由各电台自行提供。
- **音乐**：配套音乐服务可使用网易云音乐与 QQ 音乐相关资源，实际功能受对应平台服务、播放源及授权限制。
- **本地记录**：最近搜索、收听记录与播放偏好保存在设备上，用于提升使用体验。
- **第三方权益**：歌曲、封面、歌词、电台台标及商标等属于其各自权利人。本项目不提供付费平台内容的授权，也不保证第三方音源持续可用。

本项目面向个人学习与使用。请遵守所在地法律法规及相关平台的服务条款。

## 🤝 反馈与贡献

欢迎通过 [Issues](https://github.com/Meowv/tune/issues) 反馈问题、提出建议，也欢迎提交 [Pull Request](https://github.com/Meowv/tune/pulls)。

## 📄 许可证

本项目基于 [MIT License](./LICENSE) 开源，Copyright (c) 2026 阿星Plus。

---

<div align="center">
  <strong>喵听听 · Meowv Tune</strong><br />
  好歌不断，喵趣随行。<br /><br />
  Made with ❤️ by <a href="https://github.com/Meowv">Meowv</a>
</div>
