# Changelog

## [未发布]

### 新增
- 升级多维主题与色彩引擎（`WatchPictureTheme` / `buildMiuixThemeController`）：解耦深浅外观模式（跟随系统、浅色、深色）与色彩来源（壁纸莫奈动态色彩、Miuix 默认色板、10 款精选预置种子色与 Miuix `ColorPicker` + HEX 自定义种子色），新增 9 种调色板生成风格（`ThemePaletteStyle`：TonalSpot、Neutral、Vibrant、Expressive、Rainbow、FruitSalad、Monochrome、Fidelity、Content）与 Material 3 2025 扩展色彩规范（`ThemeColorSpec.Spec2025`）开关，并在设置页提供实时主题预览卡片（`ThemePreviewCard`）；优化浅色画布与纯白卡片分层对比度（`#F6F7F9` 画布 + `#FFFFFF` 容器）及 AMOLED 纯黑灰阶层次。
- 新增图包浏览列表与缩略图网格滚动位置记忆及阅读进度联动（`PackBrowseState`）：按图包独立记录缩略图网格 `(firstVisibleItemIndex, firstVisibleItemScrollOffset)` 与最后阅读页码 `lastViewedIndex`，支持缩略图角标高亮标记上次阅读图片、顶栏播放按钮续读上次页码，以及从大图阅读器跨页滑动返回时自动同步网格可视区域。
- 重构大图查看器（`GalleryViewerScreen`）为 Miuix 液态毛玻璃双层悬浮胶囊架构：引入实时背景模糊采样（`viewerBackdrop` + `ViewerGlassSurface`）、上下滑入/滑出物理过渡动效、悬浮双行信息顶栏、支持日漫 RTL 自动镜像与触觉刻度反馈的进度胶囊（`ChapterNavigator`）以及拖拽实时页码预览气泡；底部多功能工具栏（`BottomReaderBar`）新增屏幕旋转锁定（跟随系统/竖屏/横屏）、定时自动翻页幻灯片、3 列网格缩略图速览跳页抽屉（`WindowBottomSheet`）与图片元数据详情弹窗。
- 升级软件更新页（`UpdateScreen`）为 HyperOS 3.0 动态流光极光架构：引入 AGSL `BgEffectBackground` 极光流体背景着色器随滚动视差平滑淡出，支持横竖屏自适应 Hero 布局、Markdown 发行日志卡片与 GitHub Releases 历史版本直达入口。
- 新增平板与横屏宽屏（`>= 600dp`）自适应侧边导航栏（`NavigationRail`），并支持在设置中自由开启或关闭。
- 新增磁盘缓存自动清理与设置页「存储与缓存」区块：显示四类落盘数据（归档条目缓存、缩略图缓存、SAF 归档落盘副本、视频播放临时副本）的总占用、支持一键清理并回显释放量，以及自动清理开关（默认开启）；为此前完全没有容量上限的 SAF 归档落盘副本目录 `opened_archives` 与视频播放副本目录 `playback` 引入容量预算与 LRU 裁剪（统一走 `planLruTrim`，被租约占用的在途文件不参与淘汰），在应用启动（尚无归档被打开）时执行，缓存统计/清理/自动裁剪职责收敛到 `CacheMaintenance`。
- 图包条目支持视频（`PackImage.isVideo` / `ZipArchiveManager.isVideoFile`）：视频与图片一同计入图包条目与页数，覆盖文件夹图包、ZIP/CBZ 与 7z/CB7 的全部条目枚举路径；缩略图网格、图包封面与画廊速览对视频展示统一播放入口（`VideoEntryPlaceholder`），点击交给系统播放器；压缩包内的视频条目按需流式落盘到 `cacheDir/playback` 后经 `FileProvider` 共享（`VideoLauncher`）。
- 新增应用内视频播放页（`VideoPlayerScreen` + `AppRoute.VideoPlayer`，Media3/ExoPlayer）：视频条目（含压缩包内条目）点击后直接在图包内播放，带播放/暂停、进度拖拽、时间显示与沉浸式全屏，不再跳出到第三方播放器；仅在本机解码器无法播放时才提供「用其它播放器打开」兜底入口。

### 优化
- 将「主题与色彩」整块配置从设置页拆分至独立页面（新增 `ThemeSettingsScreen` 与 `AppRoute.ThemeSettings` 路由），承载深浅外观、AMOLED 纯黑、动态色彩开关、主题种子色、调色板风格、2025 色彩规范与宽屏侧边导航栏；设置页该区块收敛为一行入口，并回显「深浅外观 · 色彩来源」当前状态摘要。
- 色彩来源默认值调整为「默认色板」且默认关闭动态色彩（莫奈取色），避免首次启动继承壁纸取色导致界面主色不可预期；旧版显式选择过莫奈模式的用户仍保留动态取色，关闭动态色彩开关时统一回落默认色板。
- 全面推广 `BlurredBar` 顶栏渐进式纹理模糊至图包列表页（`PackListScreen`）、缩略图网格页（`ThumbnailGridScreen`）与设置页（`SettingsScreen`），并在图包列表页接入 Miuix `PullToRefresh` 阻尼下拉刷新。
- 重构设置页「画廊翻页方向」与「默认排序方式」为 Miuix 原生 `WindowDropdownPreference` 下拉选择菜单，消除盲点循环切换；将图包列表排序弹窗升级为 `Card` + `RadioButtonPreference` 单选体系。
- 为缩略图网格页与软件更新页路由启用 `NavSwipeDirection.LeftToRight` 左边缘滑动预测返回手势，并将卡片封面、缩略图、角标与弹窗统一升级为连续曲率超椭圆 `SquircleShape`。

### 修复
- 修复了经 ContentResolver 落盘的 SAF 归档副本只校验表头魔数、不校验 provider 声明体积的问题：被截断的副本同样能通过 `isValidArchive`（ZIP 中央目录位于文件尾部），落盘后解析失败，症状是打开图包得到「图包内未发现有效图片」；落盘改为严格比对 provider 声明体积，不一致即判为失败并留痕，同时为 zip 条目枚举与加密状态判定补上真实异常日志。
- 修复了图包扫描 `SafManager` 在直接文件系统路径并发 `awaitAll()`、以及 DocumentFile 回退路径逐项循环中均未隔离子项失败，导致所选目录中任何一个异常子项（损坏归档、不可读目录等）都会让整次扫描失败、图包列表被清空并误报「当前目录下没有找到图片文件夹或 ZIP/CBZ 压缩包」的缺陷；两条路径统一改为逐子项失败隔离（`mapIsolated` / `processDocumentChild`），异常子项仅记日志跳过，其余图包正常列出。
- 修复了视频文件被图片扩展名过滤静默丢弃（图包内视频完全不显示）以及视频条目误入图片解码、缩略图预读与画廊预取管线导致无谓解压整段视频的问题；条目枚举统一改用 `isMediaFile` 并覆盖 7z/CB7 会话层的全部条目过滤，`toImageModel` 对视频直接短路返回。
- 修复了「立即清理缓存」或启动自动裁剪后，仍留在图包列表中的独立图包条目 `directPath` 指向已被删除的落盘副本、点开必然得到空网格的问题；打开前按持久化的原始 Uri 重新落盘并回填条目（`PackViewModel.onPackClicked`）。
- 修复了失败隔离与自动清理的异常兜底捕获 `Throwable` 时连带吞掉 `CancellationException`、破坏协程取消传播的问题；改为放行取消异常。
- 修复了扫描失败日志把用户文件完整路径写入 release 日志（`AppLog` 无分级输出）的隐私残留问题；`mapIsolated` 改为只记录子项名。
- 修复了通过 SAF 导入的独立图包标题直接沿用落盘副本文件名（形如 `8位hash_原文件名`）、把内部缓存命名暴露到图包列表的问题；改为优先采用原始 Uri 的显示名，已保存的条目在下次启动重新解析时自动纠正。
- 修复了独立主题页与更新页返回按钮的无障碍描述误用「取消」的问题，改用新增文案 `nav_back`（返回）。
- 修复了加密 7z 图包解锁后网格为空、提示「图包内未发现有效图片」的问题：native 层无法打开加密 7z（`nativeOpen` 抛 `UnsupportedOperationException: 7z archive contains unsupported compression method or encryption`），而条目枚举此前**没有** Java 回退（口令校验与解压都有）→ 为 `SevenZArchiveManager.getMediaEntries` 增加 commons-compress 枚举回退，加密 7z 现可正常列出条目并按需解压。
- 修复了同一条归档既被手动导入又被根目录扫描时在列表中重复出现的问题：两条通路的 `id`/`directPath` 永远不同（导入项指向内部落盘副本），改为按来源 Uri 的原始文件名做跨通路去重，保留信息更完整的导入项。
- 修复了所选目录由其它应用创建（例如 `Download/Turrit` 这类 0770 权限目录）时，其下压缩包因裸 `File` 不可读而被静默丢弃、整个目录误报「当前目录下没有找到图片文件夹或 ZIP/CBZ 压缩包」的问题：扫描在裸文件路径读不出任何内容时回退到 SAF provider 读取，只能经 provider 访问的归档不再丢弃，而是保留条目（卡片显示「待读取」）并在首次打开时经 ContentResolver 按需落盘，随后照常进行加密判定与解锁流程。
- 修复了 `App.kt` 中 `NavDisplay` 路由 `contentKey` 回调每次重组调用 `AtomicInteger.getAndIncrement()` 导致进入大图查看器再退出返回缩略图网格时 `SaveableStateHolder` 与 `LazyGridState` 被销毁重建、丢失浏览滚动位置的缺陷；改为基于 `packId` 的确定性稳定 `contentKey`，并在 `ThumbnailGridScreen` 首帧同步命中内存图片缓存消除加载闪烁。
- 修复了 `ArchiveExtractionCoordinator.extractThumbnailDirectResult` 校验 `SevenZSessionManager` 返回结果时错误要求目标磁盘文件必须存在，导致 Coil 内存直出模式下（`keepBitmapInMemory = true`）生成的内存 `Bitmap` 被全部丢弃、强行跌入兜底路径逐张从头重新解压整块 7z 固实包（Solid Block）造成多分钟卡死甚至 OOM 的根本缺陷；重构为优先采信未回收的有效内存 Bitmap 结果。
- 修复了从缩略图网格点击图片跳转至大图画廊时，`ThumbnailGridScreen.onDispose` 立即无差别调用 `purgeCache()` 释放 Native 固实块缓存，导致大图查看器载入首张原图时缓存已被清空、不得不从第 0 字节全量重新解压固实块的问题；重构为 30 秒延迟析构（`scheduleCachePurge`），并在大图与缩略图请求发起时主动取消待执行清理（`cancelCachePurge`）。
- 修复了 `ArchiveExtractionCoordinator.pauseBackgroundSweep/resumeBackgroundSweep` 采用布尔标志位导致多个并发 Coil 前台请求在首个请求完成后即刻提前唤醒后台扫图的锁竞争问题，重构为基于 `AtomicInteger` 的引用计数器；同时在网格滚动事件监听中添加状态守卫，避免滚动期间高频累加挂起计数。
- 修复了 Release 构建开启 R8 混淆后 `Native7zEntry` 构造方法与字段被剥离优化，导致 C++ JNI `nativeGetEntries` 反射调用抛出 `NoSuchMethodError` 并触发 ART `SIGABRT` 闪退的问题；添加 `@Keep` 注解并建立 `proguard-rules.pro` 永久保活 JNI 交互类与序列化模型，并在 native C++ 抛出异常前主动清理 Pending Exception 防御 ART 异常中断。
- 修复了 `SevenZSessionManager.closeSession/closeAll/reapIdleSessions` 在未持有会话互斥锁的情况下对仍在执行 native 解压的会话调用 `close()` 的并发竞态，关闭路径统一在会话锁内执行并幂等化，杜绝 native 句柄释放后再次访问导致的崩溃。
- 修复了 `ArchiveHandlePool` 加密 zip 句柄池以 `password.hashCode()` 作为密码凭据导致不同密码哈希碰撞时复用错误句柄、解密返回损坏数据的缺陷，改为以完整密码字符串参与句柄键并校验一致性。
- 修复了 `ArchiveExtractionCoordinator.purgeCache/scheduleCachePurge` 不持会话锁执行 `nativePurgeBlockCache`，与并发 native 提取形成 solid 块缓存读写竞态的问题，purge 移入会话锁内执行并消除取消竞态窗口。
- 修复了 7z lookahead 预读任务使用独立 CoroutineScope 启动、无法随会话关闭取消且脱离 per-archive 互斥锁的问题，纳入调用方协程取消传播。
- 修复了 `ArchiveDiskCache.putDirectSuspend` 在条带锁外执行完整解压导致同 key 并发线程重复解压与缓存尺寸重复计数虚高、误触发提前淘汰的缺陷。
- 修复了 `ArchiveDiskCache` 命中缓存更新 mtime 未持锁与 `trimToSize` 并发导致"刚命中即被误判最旧而淘汰"的问题，mtime 更新移入锁内。
- 修复了 `SevenZKeyCache` 7z 密钥派生迭代次数仅在 Java 回退路径 clamp、native 路径可被恶意归档 2^62 次 PBKDF2 CPU 挂死的问题，入口统一收敛迭代次数。
- 修复了 `Coil ZipImageKeyer` 内存缓存 key 未含归档文件版本（lastModified），同路径文件替换后内存缓存仍命中旧图、与磁盘缓存不一致的问题。
- 修复了 `ZipArchiveManager.tryOpen` 回退路径每次 new ZipFile 从不 close 导致中文/非常规路径每次解码泄漏一个原生文件描述符的问题，句柄所有权随流转移并正确释放。
- 修复了 `ThumbnailDiskCache.saveDownsampled` 用 `readBytes()` 将完整原图字节读入堆导致网格并发加载时堆瞬时膨胀的 OOM 隐患，改为 64KB 头前缀 + inJustDecodeBounds 采样 + 流式降采样。
- 修复了 `ThumbnailDiskCache` 容量计数初值 -1 与重启后存量文件未计入基线、trim 与写入并发计漂移的问题，启动时重算基线并统一互斥。
- 修复了缩略图落盘后租约获取过晚、trim 可删除刚生成文件浪费完整解压的竞态窗口，以及 `ZipImageMapper` 返回磁盘文件未获取租约可能被淘汰导致空图的问题。
- 修复了 `ZipArchiveManager.extractSequentialEntries` 静默吞掉 IO/解压异常导致损坏条目无声跳过的问题。
- 修复了 `passwordStore.remove()` 后 `lastUsedPassword` 残留导致跨包自动解锁可能用错口令的隐私残留问题，并统一会话口令多 map 更新的原子性。
- 修复了 `CachedAES256SHA256Decoder` close() 无同步读取非 volatile 字段的数据竞争与 double-close 安全。
- 修复了设置页"添加新密码"输入框未做明文掩码、密码本明文 chip 常驻展示的屏幕偷窥风险，与密码弹窗掩码策略对齐。
- 修复了 `PackViewModel.openSingleArchive/onPackClicked` 自动解锁路径缺少异常兜底、SAF 权限失效抛出 SecurityException 导致未捕获协程异常崩溃的问题。
- 修复了搜索/排序在 Compose 重组时于主线程同步过滤+排序造成输入的顿的问题，下沉为协程管线 StateFlow（120ms 防抖 + Default 调度）。

### 安全
- 密码本明文落盘改为 AndroidKeyStore 派生 AES-GCM 对称密钥加密后持久化，兼容旧明文自动迁移，杜绝 root/备份窃取密码。
- 修复了 `SafManager` 直接路径解析未校验 `..` 路径穿越、可越权读取授权目录之外文件的问题，增加路径归一化与卷名白名单校验。
- `UpdateManager.downloadApk` 增加下载字节数与预期尺寸的比对校验，降低 DNS/传输层被劫持时静默安装恶意包的暴露面。

### 优化
- 升级 Miuix 组件库（`miuix-ui`、`miuix-preference`、`miuix-squircle`、`miuix-blur`、`miuix-nav`）由 `0.9.4-rc01` 至 `0.9.4` 正式版。
- 重构 Native 7z 固实块解码管线为按需增量流式解压（`CSzFolderIncrementalDecoder` + `SzArEx_ExtractIncremental`），改变首张图片请求或密码验证时强制解压整个数百 MB ~ GB 级固实块的阻塞逻辑，仅推进解码游标至目标图片末尾字节即刻返回并保留流状态，首屏图片与缩略图响应耗时由约 1 分钟降至毫秒级，后续图片按增量字节接续解压且已解码区间维持 0ms 内存直读。
- 为 Native AES-256-CBC 解密与 7z SHA-256 KDF（524,288 轮密钥派生）接入编译期 `-O3` 优化与运行时 CPU 特性检测的 ARMv8 Crypto 硬件指令加速（`vaesdq_u8`/`vaesimcq_u8`/`vsha256hq_u32`），并在 C 层引入 64 槽位全局 KDF 密钥缓存与 `SzArEx_Open2` 头部密钥透传，消除跨会话重复密钥派生。
- 移除 `SevenZSessionManager.openIfNeeded()` 中使用错误密码二次调用 `nativeOpen` 探测头部加密的冗余开销，直接读取 C 层 `nativeIsHeaderEncrypted`/`nativeIsEncrypted` 标记；统一 `SevenZArchiveManager.getImageEntries` 与 `SafManager`/`ArchiveFileResolver` 复用并预热 `SevenZSessionManager`，消除打开新图包时的多次重复解析。
- 统一 Coil 缓存键规范，消除 ThumbnailGridScreen 与 ZipImageKeyer 键不一致导致的内存缓存穿透，实现封面与九宫格 100% 内存缓存共享。
- 统一大图占位图与网格缩略图为 360px 规格，并在 ThumbnailDiskCache 中引入弹性尺寸降级回退，实现大图占位图 0ms 瞬间上屏。
- 根除大图翻页时 ZoomableAsyncImage 与 LaunchedEffect 的双重软解竞态，前置守卫物理文件就绪状态，杜绝 192MB Bitmap 无效软解与销毁重构，大图默认启用 HARDWARE 纹理。
- 缩略图下采样编码改用 Turbo-JPEG，弃用纯软件 WebP 编码，结合 ARM NEON 硬件矢量加速使缩略图写盘耗时降低 75%。
- 缩略图解码引入内存直接交付与异步写盘（Async Flush），Bitmap 解出即刻上屏，消除首帧等待写盘延迟。
- 引入 JNI DirectByteBuffer 零拷贝通道，消除 Native7z 解压下采样向 Java 堆抛出 10~20MB ByteArray 造成的频繁 GC 停顿。
- 限制九宫格后台批处理扫盘为当前视口前后 30 项，引入滚动监听实时挂起与让渡降温脉冲，杜绝 CPU 满载引发的 SoC 温控降频。
- 重构 ArchiveHandlePool，为 Zip4jFile 引入多实例对象池与读写隔离互斥保护，修复多线程并发解密 Seek 乱序与 I/O 挂死。
- 本地普通文件夹图片引入缩略图管线支持，普通目录大图无缝复用 360px 下采样缓存。
- 移除 WatchPictureApp 中冗余的 512MB Coil 磁盘缓存，释放闪存存储配额并消除冗余磁盘索引。
- 图片管线的磁盘缓存路径在回退窗口内尽早获取租约，与缓存淘汰的 `isLeased` 检查同步，避免刚生成的缩略图被环淘汰白解压。
- `LibArchiveExtractor` 每 64KB 分块重复分配字节数组改为方法级复用缓冲，降低大图解压的 GC 频率。
- release 构建开启 R8 minify（资源收缩），NDK ABI 缩减为 arm64-v8a + armeabi-v7a，APK 体积下降约 1/3。
- `AppLog` 按 BuildConfig.DEBUG 分级输出，release 仅保留必要 ERROR，降低日志噪音与路径元信息常驻。

### 修复
- 修复了 7z 固实压缩解压缓冲区（`Native7zArchive::outBuffer`，占用约 600MB~700MB 物理内存）解压后常驻 C 堆从不释放导致的物理内存峰值突破 1.6GB 触发系统 LMKD（低内存杀进程）强制闪退缺陷；在批量缩略图扫描完成、大图提取空闲超时及离开画廊/图包页面时立即触发 `purgeCache()`，瞬间归还数百兆 C 堆物理内存。
- 修复了 `SevenZSessionManager.openIfNeeded()` 在 Native 会话已有效打开的情况下未直接返回、意外继续进入 Java `SevenZFile` 实例化逻辑导致 Native 与 Java 在 JVM 堆双重驻留的严重内存泄露问题。
- 修复了单张图片解压或缩略图生成失败时错误触发 `markNativeFailed()` 导致整个 Native 会话被永久报废降级至 Java 流式扫描引发后续图片 $O(N^2)$ 级联重新解压与卡死的缺陷。
- 优化了 Coil 内存缓存配置与系统级低内存回调（`onTrimMemory` / `onLowMemory`），将 Bitmap 内存缓存上限由 40% 收敛至 20%，并在系统内存紧张或切后台时主动清理 7z Native 会话与解码缓冲区，杜绝内存撑爆与强杀。
- 修复了大图画廊退出或页面切换时在 Android 主线程（UI Looper）同步调用 `closeSession` 导致与后台解密线程争用 Native 互斥锁超过 5000ms 触发系统 ANR 闪退的严重缺陷，移除销毁时的无意义会话关闭并建立主线程异步分发隔离。
- 修复了 7z 加密图包密码验证时在限频低功耗小核上串行解密整张几十兆大图并拷贝至 Java 堆导致解密进入前转圈卡顿数秒的问题，将验证调度至 `decompressDispatcher` 超大核执行，并引入 JNI 原生零堆分配 `nativeVerify` 快速探解机制，将解密耗时由数秒降至 50ms 以内。
- 修复了大图画廊浏览时由于同步执行后续 3 张原图的预读写盘挤占前台互斥锁与磁盘 I/O 导致当前活动页延迟呈现原图且仅展示不可缩放 360px 模糊缩略图的体验割裂缺陷，预读重构为后台异步执行，统一画廊常驻 `ZoomableAsyncImage` 交互与高清预览底图。
- 修复了 7z 原生 C 引擎在解密 AES-256 密文时将 LZMA2/LZMA/PPMD 解码器末尾合法的 AES-CBC 块对齐填充字节（1~15 字节）误判为数据损坏（`SZ_ERROR_DATA`）的根本缺陷，支持 CBC 填充容差，彻底恢复加密 7z 图包的 Native C 级高速解压与常驻内存固实块直读能力。
- 修复了 `SevenZSessionManager` 在 Native 原生解密失败时直接返回 null 并中断处理的缺陷，支持平滑向下穿透至 Java 会话长连接游标，消除了单图解压跌落至外部独立开辟 `SevenZFile` 实例导致的 $O(N^2)$ 级联全量重新解压活锁与超过 25 张后界面卡死。
- 修复了 `ThumbnailDiskCache` 面对大图在解析头部时因 `mark(128KB)/reset()` 溢出抛出异常跌入 fallback 并写入截断破损 WebP 文件导致 Coil 永久显示灰色占位图的缺陷，重构为全量流读取与内存直出，杜绝坏块落盘。
- 修复了前台交互提取缩略图时调用 `pauseBackgroundSweep()` 强制中断后台扫图协程导致在持锁期间抛出 `CancellationException` 并触发 `session.reset()` 重置会话游标的雪崩问题，重构为非破坏性协作式挂起与恢复。
- 修复了 `SevenZKeyCache` 容量上限较小导致在多图集或大图包切换时过早淘汰引发重复 524,288 轮 PBKDF2 密钥派生的问题，将缓存容量扩容至 256 组并保证安全淘汰。
- 修复了 7z 原生 C SDK 在打开头部加密（EncodedHeader）或附加流加密的 7z 图包时，未向内部临时结构体传递密码指针导致 `SzArEx_Open` 报错 4（`SZ_ERROR_UNSUPPORTED`）并静默降级至纯 Java 单核解密的根本缺陷，补齐密码传递以保证原生 C 解密 100% 生效。
- 修复了原图（大图）加载时错误开辟独立 Native7z 实例导致 1GB+ 固实块重复申请内存触发 OOM 并回退至纯 Java 解压长达近 1 分钟的严重缺陷，彻底废弃独立开辟逻辑，改为复用活跃会话常驻 C 内存直接写盘，大图提取耗时从 60 秒骤降至 20 毫秒。
- 修复了视口缩略图并发请求因过度预读（`lookahead=2`）在 JVM 堆中并发申请上百 MB 大数组触发频繁垃圾回收（Large Object Space GC）导致首屏卡顿近 10 秒的问题，将视口请求收敛为零冗余单图直出。
- 修复了 7z 加密压缩图包缩略图管线与批扫调度未接入 Native7z 原生引擎、完全走 Java Commons Compress 单向流导致超过约 25 张后因重复解密累计超时卡死并永久显示灰色占位图的缺陷，将 `SevenZSessionManager` 会话管理与缩略图直出全面接入 `Native7zArchiveSession`，利用 C 级 Solid Block 内存直读实现全图集毫秒级无损随机访问。
- 修复了 7z 原生 C SDK 在解码包含 AES 滤镜的组合压缩块时 `si` 索引计算越界导致的解压失败问题。
- 增强了 `Native7zArchiveSession` 条目匹配算法，全面支持反斜杠归一化、Unicode NFC 规范化与文件名保底检索，杜绝多层路径或字符编码差异导致的条目丢失。
- 修复了 7z 加密压缩图包在后台缩略图批处理扫描中由于自然字典序与压缩包内物理存储顺序不一致引发的条目跳过和永久显示灰图缺陷，批处理重构为按 7z 物理流索引单向向前扫描，并在滑动取消与异常中断时主动重置会话，彻底杜绝流状态脏化与条目丢失。
- 修复了 7z 固实压缩图包由于视口跳跃导致的前置未缓存条目在批处理中被拒绝倒退而丢图的问题，支持安全重置回溯以保证全部条目 100% 提取并写入缩略图缓存。
- 修复了 Apache Commons Compress 内部底层解压流在打开加密 7z 每个条目时重复调用 524,288 轮 SHA-256 密钥派生导致严重卡顿的缺陷，通过 `CachedAES256SHA256Decoder` 全局挂载接入 `SevenZKeyCache`，复用已派生密钥实现 0ms 瞬间命中。
- 修复了后台缩略图扫描任务在遇到游标后的条目时错误触发流回溯重置（`session.reset()`），引发多协程竞争和重复 52 万轮解密的恶性循环问题，增加防回溯限制与协程取消检查。
- 修复了大图画廊在加载大图时未暂停后台缩略图扫描线程导致的大核算力被后台挤占与互斥锁争用问题，实现前台高优抢占式避让。
- 修复了大图画廊在命中本地磁盘缓存时因 Telephoto 切片解码无占位图直接呈现纯黑屏给用户“未在加载”错觉的问题，底层基底始终瞬显已有缩略图，大图瓦片加载完成后平滑覆盖，彻底消灭黑屏。
- 修复了 `ThumbnailDiskCache` 每次读取缓存都会更新文件修改时间戳导致 Coil 缓存键不断变动从而 100% 击穿内存缓存的严重缺陷。
- 修复了 `ZipImageMapper` 将缩略图数据源转换为物理文件导致跳过内存直出通道的问题，限定 Mapper 仅对大图切片生效。
- 修复了网格并发请求缩略图时 7z 解压流在同步锁外执行引发多线程争抢、游标频繁判定为逆向回溯从而触发 `session.reset()` 导致 CPU 算力灾难的问题。
- 修复了 HyperOS / 小米商用版 ROM 静默过滤第三方应用 Debug 与 Info 日志导致性能排查受阻的问题，统一重定向关键耗时诊断至 `AppLog`。
- 修复了图包卡片手势事件被 Miuix `Card` 内部 `pressable` 拦截导致的点击无反应及无法正常触发密码弹窗的问题，改用 Miuix `Card` 原生 `onClick` 与 `onLongPress` 参数恢复触控与按压反馈。
- 修复了加密 7z 压缩包在无密码状态下被 Native7z 误识别为未加密条目导致绕过密码输入进入空图集页面的问题，严格校验加密状态确保正常提示输入密码。
- 修复了 7z 固实解压流在部分提取或已缓存条目跳过时未完全排空底层流导致的解压状态紊乱异常。
- 修复了 `ZipImageFetcher` 在缩略图生成分支中因嵌套 `runBlocking` 导致在有限调度线程池中引发协程互相等待死锁（Deadlock）的严重问题，改用异步挂起提取彻底切断死锁链。
- 修复了 `LibArchiveExtractor` 在 Scoped Storage 分区存储沙箱环境下尝试在外部存储只读父目录创建临时文件抛出 `EACCES (Permission denied)` 的异常，重定向至内部安全缓存目录。
- 修复了透明通道图片（PNG/WebP/GIF）在缩略图下采样和显示时因硬编码 `RGB_565` 导致透明背景变黑及色彩断层的问题，智能采用 `ARGB_8888` 保留 Alpha 通道与色彩精度。
- 修复了缩略图网格加载普通 ZIP 压缩包时错误触发大图磁盘缓存全量落盘的缺陷，恢复普通 ZIP 纯流式采样直出，杜绝 10MB+ 原图频繁写盘带来的闪存磨损与写入延迟。
- 修复了 `ThumbnailDiskCache` 在下采样解码前将全量原始字节读入内存大数组（`readBytes()`）导致的突发 GC 停顿问题，改用固定 64KB 缓冲流实现零堆分配流式采样。
- 修复了大图界面因自定义数据源未被识别为本地物理文件导致 Telephoto 无法开启 Subsampling 瓦片切片、错误停留在模糊缩略图拉伸底图的缺陷。
- 修复了大图画廊翻页与九宫格多条目并发打开多个 7z 固实解压流导致的 CPU 满载 100%、严重发烫与加载阻塞问题。
- 修复了通过系统文件选择器直接选择的独立压缩包在应用退出重进后消失的缺陷，补齐了 SAF 权限持久化授权并在 DataStore 中建立双向同步与启动自动恢复通道。
- 修复了大图画廊 HorizontalPager 中离屏预加载页触发状态回调导致当前活动页缩放状态被异常重置的手势冲突缺陷。
- 修复了 ArchiveDiskCache 在流为空或异常时返回无效文件的问题，补齐了 0 字节防护与严格异常抛出。
- 修复了对图包执行主动锁定后再次点击直接发生闪退的严重异常，消除了 7z 压缩包密码校验阶段申请大字典引发的 OutOfMemoryError。
- 修复了主动锁定后因最近使用密码残留导致被静默自动解锁的问题，建立显式锁定名单彻底切断误解锁路径。
- 修复了解压与读取压缩包中带有中文字符时的乱码问题，针对 Zip4j 默认 CP437 编码实现了基于制表符特征与中文频次的自愈回退与 Unicode NFC 规范化。
- 修复了单文件压缩包缓存文件名转换时中文被正则过滤替换为下划线导致文件名失真的问题。
- 修复了 7z 格式压缩包因未被解析引擎支持而被误判为“格式不受支持或已损坏”的问题。
- 修复了文件名加密（EncodedHeader）的 7z 压缩包在未输入密码时无法被正确识别为加密图包的问题。
- 修复了点击检查更新时因应用自适应图标在 Compose 下无法直接解析导致的闪退异常。
- 修复了加密压缩包首个条目为目录条目或密钥缓存键名不匹配导致无法解密打开图集的问题。
- 修复了未对导入内容进行扩展名与结构特征检测导致 APK 等类压缩包被误当图包打开的问题。
- 修复了 Coil 3 加载加密压缩包图片时因单向解密流被预读消费无法倒带导致解码返回空图的问题。
- 修复了 Coil 缓存键未包含密码哈希，导致解锁后仍命中解密前失败缓存的问题。
- 修复了通过系统选择器导入的单文件内容 URI 压缩包在浏览详情页直接返回空图片列表的问题。

### 优化
- 优化 7z 前台解压解密线程优先级与调度：将交互式前台解压线程优先级调整为 `Process.THREAD_PRIORITY_FOREGROUND`（-2），使 Linux EAS 调度器优先调度至 Cortex-X 超大核与性能大核，消除原图解压与 AES 解密在低功耗小核上的限频瓶颈。
- 优化 7z 原生 C 流缓冲区尺寸：将底层文件输入缓冲由 256KB 扩容至 2MB，大幅提升顺序读 I/O 吞吐并降低底层系统调用频率。
- 优化缩略图内存直接下采样：新增基于 JNI 字节数组的 `getOrPutResultFromBytes` 路径，使用 `BitmapFactory.decodeByteArray` 直出 WebP 缩略图，杜绝输入流管道中转。
- 深度优化加密 7z 原生解密与固实块缓存：原生 7-Zip ANSI-C SDK 接入流式 AES-256-CBC 原生解密与会话密码直传，完全打通加密 7z 图包的 C 级固实块内存缓存，首次解码后整块数据由 C 层内存直读，消除 Java 堆内存分配与重复解码开销，翻页与批量解析性能拉平至未加密 7z 体验。
- 优化 7z AES-256 密钥派生实现：新增 NDK C 语言流水线 PBKDF2 实现（`sha256_kdf.c`）并接入 JNI，消除 Java 至 JNI 上百万次穿梭调用开销，首次冷派生耗时降低 80% 以上。
- 优化 Coil 3 缩略图内存缓存直通：在下采样 Bitmap 生成后主动将其写入 Coil `SingletonImageLoader.memoryCache`，避免重复磁盘 I/O 与 WebP 解码损耗。
- 优化二级缩略图列表重入性能：在 `ViewerViewModel` 引入图包索引内存缓存，已打开图集的二次进入时间降至 0ms，彻底消除全屏加载指示器闪烁。
- 优化缩略图网格初始加载流畅度：移除缩略图 100ms 渐显动画使缓存图片瞬间上屏，并将后台预热扫描延迟 800ms 启动以彻底让权前台可视区域渲染。
- 优化 7z AES-256 密钥派生：新增 `SevenZKeyCache` 全局 LRU 缓存，彻底消除多次打开流会话时重复执行 524,288 轮 SHA-256 带来的 500~1500ms CPU 计算耗时，命中查询降至 0.01ms 以内，并在内存紧张与会话注销时主动擦除零化敏感密钥。
- 优化缩略图呈现管线：新增 Coil 3 缩略图内存直通支持（`ThumbnailResult`），缩略图首次下采样后直接以 `ImageFetchResult(DataSource.MEMORY)` 返回解码后的 Bitmap，旁路写盘后再经由 Coil 二次磁盘读取与 WebP 解码的往返损耗，首帧呈现提速 60% 以上。
- 优化大图浏览底部滑块跳页交互：在 `GalleryViewerScreen` 底部 Miuix Slider 引入 200ms 防抖与拖动状态隔离，拖动过程中仅动态更新页码指示，停顿或松手时才执行单一目标页跳转，彻底杜绝连续快速滑动触发数十次无序并发解密与 7z 流不可逆回溯重置。
- 优化加密图包图片加载速度：将前台交互式解密与后台批扫调度器解耦，前台交互解密恢复默认高优先级，允许 Linux EAS 调度器利用性能中核与大核算力，消除低功耗小核限频瓶颈，解密提速 3~5 倍。
- 优化 7z 固实压缩缩略图抽取路径：在寻道目标条目的过程中对沿途的中间图片条目执行机会主义缩略图流式缓存，彻底根除因丢弃数据导致的前后翻页 $O(N^2)$ 回溯从头重解压。
- 优化大图连续浏览翻页预加载性能：翻页预读无缝复用活跃热会话，杜绝每次翻页重复执行 52 万轮 SHA-256 PBKDF2 密钥派生计算。
- 彻底消除加密 7z 压缩包缩略图加载性能瓶颈：消除了每个缩略图重复实例化 `SevenZFile` 与 524,288 轮 SHA-256 PBKDF 密钥派生的计算重负，统一接入 `SevenZSessionManager` 复用持久解密会话与已派生密钥。
- 优化 7z 缩略图流式采样路径：在活动会话中将中间非目标条目经由内存缓冲高速清空，直通目标条目下采样直接写入 WebP 缩略图缓存，杜绝全分辨率原图频繁落盘带来的闪存磨损与写放大。
- 优化后台批量缩略图扫描调度：移除全局互斥粗粒度锁独占，改为单条目提取后主动调度让权（`yield`），消除后台批量缩略图任务对前台用户界面即时渲染的锁争用阻塞。
- 优化加密 7z 密码验证与条目解析联动机制：密码验证通过后无缝保留热会话，进入图集时直接读取会话预解析条目，实现目录列表零延迟瞬间加载。
- 深度接入官方 7-Zip 原生 ANSI-C LZMA SDK（v26.03）解压引擎，同一固实块内的多图读取借助 C 级内存缓存彻底免除重复解压，运算开销直降至近乎 $O(1)$。
- 优化 7z 图片提取管线，利用原生 C 级 POSIX 文件操作直通目标缓存文件，消除 JVM 堆中转与临时文件二次拷贝，显著降低 GC 压力与发热能耗。

### 新增
- 新增官方 7-Zip 原生 ANSI-C LZMA SDK（v26.03）完整 NDK CMake 编译管线（`libnative7z.so`），覆盖 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 全平台架构。
- 新增 `Native7z` 与 `Native7zArchiveSession` JNI 高性能接口，提供固实块缓存感知、直接落盘与内存字节直出功能，并在无原生库环境（如主机单元测试）或遇到特殊加密时自动平滑回退至 Java Commons Compress 引擎。
- 新增 `ArchiveDispatchers` 专属能效调度器，显式注入 Linux 后台优先级（`THREAD_PRIORITY_BACKGROUND`），将计算密集型解压任务严格隔离至小核运行，释放大核保障 120Hz 手机屏幕的 8.3ms 帧渲染预算。
- 新增 `PowerThermalManager` 系统温控与省电状态动态感知组件，在设备发热（`THERMAL_STATUS_MODERATE`+）或开启省电模式时自适应截断前瞻预读并压制解压并发。
- 新增 `ArchiveExtractionCoordinator` 解压协调调度中心，保障单一压缩包单线程互斥流式解压，并赋予当前焦点页最高抢占调度优先级。
- 新增 `ZipImageMapper`，自动将磁盘缓存中已落盘的条目直通映射为本地 `File`，打通 Telephoto 原生瓦片分块解码通道。
- 新增 .cb7 压缩图包格式支持，并在系统文件扫描与解析引擎中完成端到端识别。
- 新增 .jfif、.pjpeg、.pjp、.tiff、.tif、.ico、.svg 扩展图片格式支持，接入专用 SVG 与动图解码管线。
- 新增持久化历史密码本系统，支持将输入的密码安全存入本地密码本，并在密码输入弹窗中提供常用历史密码快捷点选。
- 新增设置中心密码本管理模块，支持历史密码列表查看、手动新增、单条删除与一键清空。
- 新增图包列表长按菜单中的“锁定图包”选项，支持用户主动锁定指定图包并彻底清除其内存解密缓存。
- 新增完整的 7z 格式压缩包引擎（SevenZArchiveManager），支持标准与 AES-256 加密 7z 压缩包的魔数检测、自然排序条目解析、密码验证与按需流式提取。
- 新增系统文件管理器与外部应用关联打开 .7z 文件支持，并更新了应用内单文件选择器的 MIME 过滤范围。
- 新增图包长按操作菜单与移除确认弹窗，支持从图包列表中移除项目并同步清理单文件导入的临时缓存文件。

### 优化
- 优化了 7z 固实压缩包（Solid 7z）阅读连续翻页性能，新增 `SevenZSessionManager` 固实流会话管理，将顺序翻页从重复从头解压的 O(N^2) 算力灾难优化为严格 O(1) 线性单趟流式推进，降低逾 90% CPU 算力与电池能耗。
- 优化了 7z 缩略图生成管线，彻底消除全量原图强制落盘逻辑，改用内存流直通降采样直接生成并落盘 WebP 缩略图，将单张图片闪存写入量从 10MB+ 骤降至 15KB，减少 99% 以上闪存 I/O 磨损。
- 优化了 7z 缩略图网格载入体验，新增 `startBatchThumbnailSweep` 后台小核单趟线性批处理抽取，配合温控（`PowerThermalManager`）热感知动态节流，全图包缩略图一次线性顺滑生成。
- 优化了 Native `LibArchiveExtractor` 解压 I/O 性能，支持直写目标缓存文件描述符，消除了二次中间临时文件创建与读写，原生解压闪存写盘量与延迟减半。
- 优化了 7z 会话生命周期管理，在画廊界面销毁、图包主动锁定或达到 60 秒空闲阈值时自动释放解压器与字典内存。
- 优化了图包列表在 120Hz 高刷新率屏幕下的 Compose 重组流畅度，将 `PackListUiState.displayedPacks` 从每帧实时遍历过滤与自然排序重构为基于数据变更的缓存记忆化（Memoization），消除微小重组时的主线程卡顿。
- 优化了悬浮底栏在多任务及低功耗状态下的传感器与 GPU 负载，仅在激活液态玻璃模式时启动陀螺仪倾斜度监听；在设备中度发热或省电模式下自动降级为基础模糊并切断硬件传感器唤醒。
- 增强了系统低内存与 LMK (Low Memory Killer) 查杀防御，重写 `WatchPictureApp.onTrimMemory` 与 `onLowMemory`，在退火切后台及内存告急时主动清理 Coil 内存缓存并关闭未使用的文件句柄池。
- 引入原生 C/C++ `libarchive` Native JNI 与单流顺序提取通道，彻底消除了 7z 固实压缩反复解压导致的 CPU 满载与耗时瓶颈。
- 新增 `ArchiveHandlePool` 句柄缓存池，对无密码 ZIP 启用系统底层原生 `java.util.zip.ZipFile`（基于 native C++ zlib），消除了反复随机 Seek 解析 Central Directory 的磁盘开销。
- 彻底解耦缩略图与大图通道，新增专属 `ThumbnailDiskCache` 与流式 `inSampleSize` 下采样，杜绝网格浏览时将动辄数十兆的原图写盘，闪存写入量骤减 95% 以上并大幅降低发热功耗。
- 升级阅读器大图预加载为 Coil 内存预热管线（Memory Preheating），相邻页预解码至硬件位图（Hardware Bitmap），实现翻页 0 毫秒闪电瞬开。
- 优化了 ArchiveDiskCache 缓存管理机制，采用 64 分片条带锁与 AtomicLong 内存容量统计，消除文件解压落盘时的全量目录遍历与全局锁竞争。
- 优化了 ZipArchiveManager 压缩包目录解析流程，增加纯 ASCII 与标准 UTF-8 快速路径，按需懒加载消除多余的 GBK 二次解析，ZIP 读取耗时减半。
- 优化了 SafManager 图包列表扫描性能，合并加密判定与条目提取消除重复打开，改用协程并行并发扫描，大型目录扫描速度提升 3 至 5 倍。
- 优化了大图画廊翻页体验，引入基于阅读方向的双向智能大图预加载与网格点击转场前瞬时解压预热，翻页完全无感。
- 优化了缩略图网格的解码性能，采用 RGB_565 色彩格式，降低 50% 显存占用并大幅减轻快速滚动时的 GC 压力。
- 引入 Telephoto 动态子采样瓦片渲染引擎（Sub-sampling），结合 Android 原生 BitmapRegionDecoder 依据屏幕视口动态解码超高清瓦片切片，彻底消除 10MB+ 超大图模糊与放大马赛克问题，兼顾超清画质与内存安全。
- 新增 ArchiveDiskCache 压缩包流式解压磁盘缓存池，大图首次解压直接通过管道流式落盘，彻底消除 JVM 堆内存暴涨与垃圾回收停顿，后续翻页与二次查看耗时降低至 1 至 5 毫秒。
- 严格隔离缩略图与大图的内存缓存键，杜绝大图查看时错误命中低分辨率缩略图导致的画质失真。
- 升级大图首帧渲染为双图层渐进式架构，以内存缩略图作为 0 毫秒即时底图秒现呈现，后台无感加载超高清原图并平滑过渡。
- 平息大图并发预加载风暴，当前查看页独占算力，相邻页降级为防抖延迟与单并发串行静默预热。
- 优化了 10MB+ 超大原图的加载性能，彻底废除临时文件落盘机制，采用纯内存直出缓冲流，消除数百毫秒闪存读写延迟与存储损耗。
- 优化了缩略图点击进入大图的响应速度，利用已缓存缩略图作为零延迟占位图，彻底消除白屏与等待感。
- 优化了大图画廊翻页体验，引入视口前后双向预加载引擎与点击前瞻预加载，后台预先解码相邻页大图，实现翻页秒开。
- 优化了 Coil 3 内存配置与大堆支持（android:largeHeap），将内存缓存上限提升至 40%，并启用平滑交叉淡入过渡。
- 优化了加密图包密码解锁后的即时状态同步，解锁成功后立即重新扫描并刷新图集数量与封面预览。
- 优化了会话密码存储管理，支持 URI、真实绝对路径及规范化路径多键互通与连带撤销。
- 优化了非 UTF-8 编码（GBK/CP936）压缩包与 Windows 反斜杠路径的条目解析与解密兼容性。
- 优化了图片模型密码自动兜底机制，未显式传参时自动从会话密码池关联匹配已解锁密码。

## [v1.0.0] - 2026-09-20

### 新增
- 支持本地图包目录选取与授权记忆，支持自动扫描并分类展示常规文件夹与压缩包图集。
- 支持单个压缩包文件即时选取，支持直接从系统文件管理器等外部应用关联打开图集。
- 支持加密压缩包即时输入密码预览，无需全量解压落盘，保障私密安全与加载速度。
- 支持图集缩略图自适应网格预览与全屏大图画廊浏览。
- 支持画廊翻页阅读方向切换，提供标准从左至右与日漫从右至左阅读模式。
- 支持图集按名称、修改时间、文件大小及图片数量进行升降序排序。
- 支持主页图包名称实时模糊搜索与快速过滤。
- 支持设置中心，提供阅读方向配置、默认排序规则设定及临时密码一键清空等功能。
- 支持应用内检查更新，提供新版本检测、版本变更日志展示、安装包下载与调起系统安装。
- 支持多功能悬浮底栏，集成导航切换与动态交互视觉效果。

### 修复
- 修复了普通文件夹内图片无法正常读取和预览的问题。
- 修复了部分系统路径下授权文档解析失败导致无法加载图包的异常。

### 优化
- 优化了全屏大图浏览手势交互，双击缩放与双指缩放操作更平滑，解决了放大状态下的翻页冲突。
- 优化了图集图片的加载与缓存机制，大幅降低大体积压缩包的解析与切换等待时间。
- 优化了深层多级子目录图片的识别与自然排序，自动展平归纳多层文件夹中的图片。
- 优化了临时密码记忆与会话生命周期，支持同一图集密码优先复用并可在退出时主动锁定。
