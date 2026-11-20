# WatchPicture — Agent 约束入口

轻量高性能安卓「图包快捷查看器」：常规文件夹、ZIP / CBZ、7z / CB7 压缩包、加密压缩包（AES-128/256、ZipCrypto）的内存级流式浏览。Kotlin 2.3.21 + Jetpack Compose + Miuix (`top.yukonga.miuix.kmp:0.9.4`) + Coil 3 + Zip4j + libarchive，原生引擎 `NativeZip` / `Native7z` / `LibArchiveExtractor`。

## 构建与验证

- 环境：JDK 21、Android SDK Platform 37（`compileSdk = 37`、`targetSdk = 35`、`minSdk = 24`）、CMake 3.22.1、NDK `29.0.14206865`
- 单元测试：`./gradlew testDebugUnitTest`
- Release 包：`./gradlew assembleRelease`（产物 `app/build/outputs/apk/release/app-release-unsigned.apk`）
- 完成标准 = 编译（含测试）通过。

## 项目约定（硬约束）

- **严禁全量解压落盘**：压缩包一律内存级流式读取（`NativeZip` / `Native7z` / `LibArchiveExtractor` + Zip4j + Commons Compress + Coil 3 自定义 `Fetcher` 管道），大体积压缩包即开即显。豁免：图片解码 / 视频播放等无法流式消费的场景，允许单条目按需落盘到受容量预算约束的缓存目录（如 `cacheDir/playback`），仍严禁整包解压落盘。
- **密码不落盘**：加密包密码只驻留内存会话，禁止任何形式的明文磁盘持久化。
- UI 规范：严格使用 `top.yukonga.miuix.kmp` 官方组件（`TopAppBar` + `MiuixScrollBehavior` 大标题折叠、`WindowDialog` 密码弹窗、`WindowBottomSheet` 抽屉、`Slider` 跳页），严禁引入 Material 3 控件；悬浮胶囊底栏是自研 `ui/component/bottombar/FloatingBottomBar.kt`，不替换为库底栏。
- 存储访问：SAF 授权持久化兼容，同时保留直接文件系统路径的极速扫描。

## 文档索引

| 何时读 | 文档 |
|---|---|
| 功能特性、技术栈、构建方式 | `README.md` |
| 更新日志 | `CHANGELOG.md` |
| vendored libdeflate（ZIP inflate 后端）源码说明 | `app/src/main/cpp/zip_engine/README.md`、`app/src/main/cpp/zip_engine/NEWS.md` |
