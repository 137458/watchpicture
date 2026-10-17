# WatchPicture

WatchPicture 是一款轻量、高性能的安卓「图包快捷查看器」应用。深度遵循 HyperOS / Miuix 视觉与交互规范，支持常规文件夹、普通压缩包（ZIP / CBZ）、7z 压缩包以及密码保护加密包（AES-128 / AES-256 / ZipCrypto）的高速内存级流式浏览。

## 核心特性

- **Miuix 原生交互体验**：基于 `top.yukonga.miuix.kmp` 官方组件库构建，具备大标题折叠 TopAppBar、触感反馈 MiuixCard、WindowDialog 密码弹窗、MiuixSlider 快速跳页控制器与悬浮胶囊底栏。
- **内存级流式读取，严禁全量解压落盘**：采用自研 Native 引擎、Commons Compress 与 Coil 3 自定义 `Fetcher` 管道，所有解压与解密在内存管道中流式消费，大体积压缩包即开即显。对于无法流式消费的视频播放等场景，单条目按需落盘至受容量预算与保护窗约束的缓存目录。
- **多引擎分级协同架构**：
  - **Native C++ 引擎**：基于 mmap 实现，支持 ZIP 中央目录单次解析与哈希索引，STORED 条目零拷贝直通，整包扫描零堆内存增长。
  - **池化句柄流式解压**：高并发池化 `java.util.zip` 与条目流按核并行调度，消除重复解析的开销。
  - **7z 固实块生命周期驱动回收**：固实块缓存与页面导航生命周期及系统内存压力事件联动，彻底消除翻页重复解压与内存悬垂；集成 Vendored 7z 解码器与 PBKDF2 密钥缓存。
- **自适应缩略图全量预缓存系统**：
  - 基于 `ThumbnailSweepQueue` 串行队列进行整包后台预扫描，支持重复触发自动合并。
  - 前台滑动画廊取图时通过协作机制自动抢占 CPU 与条带锁（`pauseBackgroundSweep` / `resumeBackgroundSweep`），滑动停止后自动续跑。
  - 智能落盘编码策略：不含 Alpha 通道条目走极速 JPEG 编码，透明图像自动回退 WEBP。
  - 缓存清单 2 秒 TTL 复用，消除全量扫描时的重复枚举开销。
- **临时会话密码隔离**：针对加密图包采用只驻留内存的密码会话管理，禁止任何形式的明文磁盘持久化。密码字节在回收与驱逐时主动清零（Zeroize），磁盘缓存键仅嵌入单向 SHA-256 摘要。
- **看图页一键导出**：集成 `MediaExportManager`，支持将当前查看的图片或视频通过系统 Storage Access Framework（SAF）「另存为」导出至用户指定位置。支持真实文件直拷、归档条目内存流式转存与 SAF URI 转发。
- **全功能手势画廊与多媒体支持**：基于 HorizontalPager 实现平滑翻页，支持双击缩放、双指无级缩放与边界平移，支持单击切换沉浸式全屏；支持图包内视频条目直接调用 ExoPlayer 播放。
- **存储访问兼容**：全面兼容 Android Storage Access Framework（SAF）授权持久化，并兼顾直接文件系统访问路径极速扫描。

## 技术栈与依赖

- 运行时与平台：Kotlin 2.3.21, Android SDK 37 (Min SDK 24, Target SDK 35), Java 21, CMake 3.22.1, NDK 29
- UI 框架：Jetpack Compose + Miuix Compose (`top.yukonga.miuix.kmp:0.9.4`)
- 图片渲染：Coil 3 (`io.coil-kt.coil3:3.0.4`)
- 归档解析引擎：自研 Native C++ 引擎 + Commons Compress + Zip4j
- 视频播放：Media3 / ExoPlayer
- 依赖管理：Gradle Version Catalog (`gradle/libs.versions.toml`)
- 架构设计：Kotlin Coroutines + StateFlow (MVVM + Clean Architecture)

## 构建与测试

执行本地单元测试：
```bash
./gradlew testDebugUnitTest
```

构建 Release 安装包（开启 R8 混淆与原生符号优化）：
```bash
./gradlew assembleRelease
```
产物位置：`app/build/outputs/apk/release/app-release-unsigned.apk`
