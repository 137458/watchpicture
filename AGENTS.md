# WatchPicture — Agent 约束入口

轻量高性能安卓「图包快捷查看器」：常规文件夹、ZIP / CBZ 压缩包、加密压缩包（AES-128/256、ZipCrypto）的内存级流式浏览。Kotlin 2.3.21 + Jetpack Compose + Miuix (`top.yukonga.miuix.kmp:0.9.4`) + Coil 3 + Zip4j。

## 构建与验证

- 环境：JDK 21、Android SDK 37（minSdk 24）
- 单元测试：`./gradlew testDebugUnitTest`
- Release 包：`./gradlew assembleRelease`（产物 `app/build/outputs/apk/release/app-release-unsigned.apk`）
- 完成标准 = 编译（含测试）通过。

## 项目约定（硬约束）

- **严禁全量解压落盘**：压缩包一律内存级流式读取（Zip4j 引擎 + Coil 3 自定义 `Fetcher` 管道），大体积压缩包即开即显。
- **密码不落盘**：加密包密码只驻留内存会话，禁止任何形式的明文磁盘持久化。
- UI 规范：严格使用 `top.yukonga.miuix.kmp` 官方组件（大标题折叠 TopAppBar、WindowDialog 密码弹窗、MiuixSlider 跳页），严禁引入 Material 3 控件。
- 存储访问：SAF 授权持久化兼容，同时保留直接文件系统路径的极速扫描。

## 文档索引

| 何时读 | 文档 |
|---|---|
| 调研文档（加密归档加载、图片加载性能） | `docs/research/` |
| 更新日志 | `CHANGELOG.md` |
