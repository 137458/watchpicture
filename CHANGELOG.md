# Changelog

All notable changes to this project will be documented in this file.

## [未发布]

### 新增
- 单文件图集压缩包选取与即时打开：在顶部操作栏与空状态引导卡片中引入基于 SAF OpenDocument 的文件选择器，支持直接点选 `.zip` 与 `.cbz` 单文件压缩包，无缝衔接密码鉴权与缩略图预览。
- 系统外部关联打开支持：在 AndroidManifest.xml 中注册常见压缩包 MIME 类型（application/zip、application/x-zip-compressed、application/x-cbz 等）与文件后缀关联打开 Intent-filter，并将 MainActivity 配置为 singleTask，支持冷启动与热启动（onNewIntent）外部唤醒调度。
- 零拷贝直读与高性能流式解析器（ArchiveFileResolver）：优先通过底层路径映射实现零拷贝直读，后备采用 64KB 高速缓冲流式缓存与轻量缓存复用，结合 4 字节 ZIP 魔数极速初筛。
- 主页图包列表暂存与合并展示：单选或外部打开的单文件压缩包自动抽象为 ZipPack 实体暂存入主页图包列表中，支持去重合并、动态置顶与返回复访。
- Miuix 悬浮底栏（FloatingBottomBar）：集成「图包」与「设置」双 Tab 导航，支持液态玻璃折射、双重离屏采样渲染（CombinedBackdrop）、阻尼弹性拖拽手势与低功耗陀螺仪感应随动高光，并支持多层次三态兼容降级。
- 偏好设置界面（SettingsScreen）：遵循 Miuix 卡片与偏好设计规范，支持画廊翻页方向选择、默认排序配置、内存临时解密密码一键清除及版本更新中心入口。
- 版本检测与更新体系（UpdateScreen / UpdateDialog / UpdateManager）：对接 GitHub Releases，实现自动与手动检查更新、SemVer 规范版本比对、Compose Markdown 变更日志渲染、断点续传下载、实时速率计算、FileProvider 调起系统包安装及浏览器直链下载降级。
- 核心存储与 SAF 授权管理：支持通过系统文件选择器选取本地根目录，并持久化 URI 访问权限；兼容直接文件路径极速扫描。
- 图包扫描与识别引擎：统一抽象普通文件夹（DirectoryPack）与压缩包（ZipPack），支持 `.zip` 与 `.cbz` 格式。
- 加密压缩包流式解密：基于 Zip4j 内存级按需解压，通过 `zipFile.getInputStream(fileHeader)` 流式提取图片数据，杜绝全量落盘。
- Coil 3 自定义图片加载管道：实现 `ZipImageSource`、`ZipImageKeyer` 与 `ZipImageFetcher`，内存流式直传解码。
- 安全密码隔离池：实现内存级会话密码存储器 `SessionPasswordStore`，图包退出或切换时即时清理，严禁明文持久化。
- Miuix 风格密码鉴权弹窗：基于 `WindowDialog` 与 `TextField` 实现密码显隐切换、快速清空与震动触感反馈。
- 图包列表主页 (`PackListScreen`)：集成 `MiuixTopAppBar` 大标题折叠吸顶、`MiuixCard` 触感卡片、加密角标与格式/页数 Badge。
- 图包缩略图预览 (`ThumbnailGridScreen`)：自适应网格快速加载，支持点击跳入全屏画廊。
- 全屏大图画廊 (`GalleryViewerScreen`)：基于 `HorizontalPager` 实现丝滑翻页，支持双击缩放、双指 Pinch-to-zoom、单指平移、单击沉浸式切换及 `MiuixSlider` 毫秒级跳页。
- 图包排序与即时搜索过滤：新增 `PackSorter` 与 `PackFilter`，支持按名称、修改时间、文件大小、图片张数升降序排序与模糊关键词检索。
- 深度嵌套目录识别：新增 `DeepFolderImageResolver`，支持递归检索图包子目录图片并按自然排序统一展平。
- 根路径状态持久化：通过 `PreferencesRepository`（Jetpack DataStore）保存用户已授权的根目录，冷启动免去重复授权选择。
- 画廊手势防冲突与平滑缩放：大图放大时动态解除 Pager 翻页拦截，基于 `Animatable` 实现以触摸点为中心平滑缩放与严格视口边界限制。
- 日漫模式 (RTL) 与阅读方向切换：画廊支持标准从左到右 (LTR) 与日漫从右到左 (RTL) 翻页切换。
- 会话密码优化与主动重置：支持优先复用最近成功密码尝试静默解密，并可在缩略图预览与大图画廊中主动加锁注销内存临时密码。

### 修复
- 目录图包模型解析：修复普通文件夹内图片被误当做 ZIP 压缩包传入 ZipArchiveManager 的缺陷，统一通过 `PackImage.toImageModel()` 分发。
- SAF 文件路径解析：修复 `SafManager.resolveDirectFile` 处理树内文档 URI 时未优先提取子文档 ID 的缺陷，并消除空 Context 引发的潜在空指针异常。
