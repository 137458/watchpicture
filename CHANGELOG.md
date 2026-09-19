# Changelog

All notable changes to this project will be documented in this file.

## [未发布]

### 新增
- 核心存储与 SAF 授权管理：支持通过系统文件选择器选取本地根目录，并持久化 URI 访问权限；兼容直接文件路径极速扫描。
- 图包扫描与识别引擎：统一抽象普通文件夹（DirectoryPack）与压缩包（ZipPack），支持 `.zip` 与 `.cbz` 格式。
- 加密压缩包流式解密：基于 Zip4j 内存级按需解压，通过 `zipFile.getInputStream(fileHeader)` 流式提取图片数据，杜绝全量落盘。
- Coil 3 自定义图片加载管道：实现 `ZipImageSource`、`ZipImageKeyer` 与 `ZipImageFetcher`，内存流式直传解码。
- 安全密码隔离池：实现内存级会话密码存储器 `SessionPasswordStore`，图包退出或切换时即时清理，严禁明文持久化。
- Miuix 风格密码鉴权弹窗：基于 `WindowDialog` 与 `TextField` 实现密码显隐切换、快速清空与震动触感反馈。
- 图包列表主页 (`PackListScreen`)：集成 `MiuixTopAppBar` 大标题折叠吸顶、`MiuixCard` 触感卡片、加密角标与格式/页数 Badge。
- 图包缩略图预览 (`ThumbnailGridScreen`)：自适应网格快速加载，支持点击跳入全屏画廊。
- 全屏大图画廊 (`GalleryViewerScreen`)：基于 `HorizontalPager` 实现丝滑翻页，支持双击缩放、双指 Pinch-to-zoom、单指平移、单击沉浸式切换及 `MiuixSlider` 毫秒级跳页。
