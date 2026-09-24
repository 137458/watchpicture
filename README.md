# WatchPicture

WatchPicture 是一款轻量、高性能的安卓「图包快捷查看器」应用。深度集成 HyperOS / Miuix 风格组件库，核心支持常规文件夹、普通压缩包（ZIP / CBZ）以及带密码保护的加密压缩包（AES-128 / AES-256 / ZipCrypto）的高速流式浏览。

## 核心特性

- **Miuix 原生交互体验**：基于 `top.yukonga.miuix.kmp` 构建，具备大标题折叠 TopAppBar、触感反馈 MiuixCard、WindowDialog 密码弹窗与 MiuixSlider 快速跳页控制器。
- **内存级流式解密**：采用 `net.lingala.zip4j` 引擎与 Coil 3 自定义 `Fetcher` 管道，严禁全量解压落盘，实现大体积压缩包即开即显。
- **临时会话密码隔离**：针对加密图包采用只驻留内存的密码会话管理，禁止任何形式的明文磁盘持久化。
- **全功能手势画廊**：基于 HorizontalPager 实现平滑翻页，支持双击缩放（1x / 2.5x）、双指无级缩放与边界平移，支持单击切换沉浸式全屏。
- **存储访问兼容**：支持 Android Storage Access Framework (SAF) 授权持久化，并兼顾直接文件系统访问路径极速扫描。

## 技术栈与依赖

- 运行时与平台：Kotlin 2.3.21, Android SDK 37 (Min SDK 24), Java 21
- UI 框架：Jetpack Compose + Miuix Compose (`top.yukonga.miuix.kmp:0.9.4`)
- 图片渲染：Coil 3 (`io.coil-kt.coil3:3.0.4`)
- 压缩包引擎：Zip4j (`net.lingala.zip4j:2.11.5`)
- 数据流与状态：Kotlin Coroutines + StateFlow (MVVM + Clean Architecture)

## 构建与测试

执行本地单元测试：
```bash
./gradlew testDebugUnitTest
```

构建 Release 安装包：
```bash
./gradlew assembleRelease
```
产物位置：`app/build/outputs/apk/release/app-release-unsigned.apk`
