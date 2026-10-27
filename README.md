# SnapOCR - Android 全局悬浮截屏识字与文字提取器

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Language-Kotlin_1.9-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Engine-Google_ML_Kit-4285F4?logo=google&logoColor=white" alt="Google ML Kit" />
  <img src="https://img.shields.io/badge/Design-Material_3-blue" alt="Material 3" />
  <img src="https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white" alt="GitHub Actions" />
  <img src="https://img.shields.io/badge/License-Apache_2.0-orange" alt="License" />
</p>

**SnapOCR** 是一款专为 Android 打造的高性能、轻量化、**离线隐私安全**的全局屏幕文字识别提取工具。

无论是查看无法复制文字的应用（如小红书、微信朋友圈、Instagram、图片 PDF、视频字幕、游戏画面），只需轻点屏幕边缘的悬浮球，即可在 **毫秒级** 内完成全屏截屏识别。支持**原生双向水滴手柄自由拖选词句**、**识别即入剪贴板**，以及**相册多图批量 OCR 识别**。

---

## 🌟 核心功能亮点

### 1. ⚡ 极速悬浮截屏，点按即识
- **一键截取当前屏幕**：轻量级屏幕边缘悬浮球，不遮挡操作。点击悬浮球瞬间自动隐藏自身 150ms 避免入镜，静默捕获当前屏幕画面并启动 OCR。
- **自动入剪贴板 (Auto-Copy)**：识别完成的全部文字第一时间写入系统剪贴板，并在后台即时弹送轻量 Toast 提示，省去任何多余点击。
- **灵动悬浮球交互**：支持屏幕边缘自由拖曳吸附，支持应用内一键启动/关闭悬浮球服务。

### 2. 💧 原生水滴光标手柄与自由拖选
- **真·原生自由选择交互**：区别于普通悬浮窗截断系统手柄的问题，SnapOCR 采用专属透明 Dialog Activity 架构，完整挂载系统级 `Window.Callback`。
- **双向水滴形拖拽光标**：长按任意词句立即唤出**左侧与右侧双水滴光标手柄**，左右拉动即可自由扩大/缩小选取区间。
- **系统级浮动操作栏**：弹出原生的 `[复制]`、`[全选]` 快捷菜单，与系统自带编辑体验 100% 一致。
- **弹窗快捷操作栏**：
  - `[全选]`：一键全选所有段落并高亮显示。
  - `[复制选中 (X字)]`：实时统计当前选取的字符数，点击仅复制选取段落。
  - `[一键全选复制]`：快速复制全文并关闭弹窗。

### 3. 🧠 100% 离线隐私安全 (Google ML Kit)
- 基于 Google ML Kit On-Device Text Recognition 引擎（支持中文、英文、数字、标点符号混排识别）。
- **零网络上传**：所有图像识别与处理均在手机本地 NPU/CPU 完成，不消耗流量，无任何服务器后端，完全保护用户隐私与敏感商业信息。

### 4. 🖼️ 相册多图批量识别
- 支持一次性从系统相册选取多张图片进行并行识别。
- 清晰的卡片流列表展示识别结果、字符数统计与识别用时。
- 支持单条结果快速复制与再次预览。

### 5. 📲 系统级“分享”无缝接收
- 支持从手机相册、文件管理器、浏览器或其他应用中，点击“分享到 SnapOCR”，即可直接唤起应用提取图片中的文字。

---

## 🏗️ 架构与技术实现

```
com.snapocr.app
├── MainActivity.kt               # 主界面控制：悬浮球权限引导、状态同步、相册多图选择与系统分享处理
├── service
│   └── FloatingCaptureService.kt # 核心前台服务：MediaProjection 录屏截屏、GPU 缓冲安全转换、悬浮球生命周期
├── ui
│   ├── FloatingResultActivity.kt # 沉浸式透明 Dialog：激活 Android 原生 Editor 水滴手柄与 ActionMode 浮动菜单
│   └── OcrResultAdapter.kt       # 主界面批量识别结果列表适配器
├── ocr
│   └── OcrManager.kt             # ML Kit 离线文字识别封装，Kotlin 协程并发调度
└── model
    └── OcrResult.kt              # 识别结果实体数据模型
```

### 关键技术攻关与突破：

1. **GPU 渲染跨度内存安全拷贝 (Stride Buffer Fix)**：
   Android 各家 GPU 芯片（Adreno, Mali 等）在 `ImageReader` 捕获帧时，末行往往省略填充字节（Padding）。传统方式直接取 `planes[0].buffer` 会导致 `Buffer not large enough for pixels` 崩溃。SnapOCR 实现了**精确计算的直接内存缓冲区分配与 rewind 保护机制**，在确保 0 内存拷贝浪费的同时彻底消除崩溃。

2. **解决悬浮窗无法显示原生水滴光标手柄的系统级限制**：
   在 Android 操作系统源码中（`Editor.java` / `TextView.java`），文本选择的水滴手柄和浮动菜单依赖 `startActionMode(..., TYPE_FLOATING)`，而系统层在判断时需要父窗口具备真实的 `PhoneWindow` 与 `Window.Callback`。在 Service 中通过 `WindowManager.addView()` 添加的普通 View 无法获取有效的 Window 回调，导致手柄无法初始化。
   
   SnapOCR 巧妙设计了 `@style/Theme.SnapOCR.Dialog` 属性的 `FloatingResultActivity`，结合 `excludeFromRecents="true"` 和轻量半透明背景，既能像悬浮窗一样即点即现、点击外围即散，又拥有完整的操作系统窗口能力，**彻底激活原生双向拖拽水滴手柄**。

---

## 📦 快速下载与安装

### 方式 A：GitHub Actions 自动构建（推荐）

本项目已接入 **GitHub Actions CI/CD** 自动化构建流程，每次代码提交均会自动编译出最新的 Debug APK：

1. 打开 GitHub 仓库页面：[https://github.com/Brandon-Winterfell/SnapOCR](https://github.com/Brandon-Winterfell/SnapOCR)
2. 点击顶部的 **Actions** 标签栏。
3. 选择最新一次成功的构建工作流（**Build Android APK**）。
4. 在页面底部的 **Artifacts** 区域，点击下载 **`SnapOCR-Debug-APK`**（zip 包）。
5. 解压后将 `app-debug.apk` 发送至手机安装即可。

### 方式 B：本地源码编译

#### 1. 环境准备
- **操作系统**：Windows / macOS / Linux
- **开发工具**：Android Studio Hedgehog (2023.1.1) 或更高版本
- **JDK**：OpenJDK 17
- **Android SDK**：API 34 (Android 14)
- **最低运行要求**：Android 8.0+ (API 26)

#### 2. 克隆代码并编译
```bash
# 克隆仓库
git clone https://github.com/Brandon-Winterfell/SnapOCR.git
cd SnapOCR

# 给 gradlew 赋予执行权限 (Linux/macOS)
chmod +x gradlew

# 编译 Debug APK
./gradlew assembleDebug
```

编译生成的 APK 文件位于：
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔐 权限说明

为了提供稳定、流畅的屏幕截取与识别体验，SnapOCR 在运行时需要以下权限：

| 权限名称 | 对应系统 API | 用途说明 |
| :--- | :--- | :--- |
| **悬浮窗权限** | `SYSTEM_ALERT_WINDOW` | 用于在屏幕上悬浮显示截屏悬浮球控制器。 |
| **屏幕录制/截屏授权** | `MediaProjection` | 仅在您点击悬浮球时截取单帧画面用于 OCR 识别，绝不录屏，不会保存或上传。 |
| **前台服务权限** | `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ 系统规范要求，保证截屏服务在后台稳定存活。 |
| **通知权限** | `POST_NOTIFICATIONS` | 用于展示截屏前台服务常驻通知及识别完成快速操作提醒。 |
| **读取相册图片** | `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` | 仅在您主动使用“相册批量识字”功能时读取您选定的图片。 |

> 🔒 **隐私承诺**：SnapOCR 绝不收集、存储或上传您的任何屏幕截图、剪贴板内容或识别文字。所有计算全部在设备本地芯片闭环完成。

---

## 🛠️ 技术栈清单

- **编程语言**：[Kotlin](https://kotlinlang.org/) 1.9
- **核心算法**：[Google ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/android) (Latin + Chinese v16.0.0)
- **并发异步**：Kotlin Coroutines & Flow
- **UI 设计系统**：Google Material Design 3 (Material Components 1.11.0)
- **系统适配**：Android Jetpack (Core-KTX, AppCompat, Activity-KTX, Lifecycle)
- **构建系统**：Gradle 8.4 + Android Gradle Plugin 8.3

---

## 📄 开源许可证

本项目基于 [Apache License 2.0](LICENSE) 协议开源。欢迎提交 Issue 或 Pull Request 共同完善！
