# 安卓下载页黑封面与刷新退出排查（2.0.2.5.6）

## 结论与证据边界

用户报告：安卓下载项超过 1,000 条，打开下载页只有极少数封面可见，其余纯黑；此时刷新单项会闪退。这是现场现象，不代表已取得堆栈或已在真机复现。

基于上一版本 `1567fa8` 的代码审查，确认多个封面生命周期、重复调度与取消阻塞缺陷，已修复。尚无现场 logcat、ANR trace、native tombstone 或内存记录，不能确定此次退出属于 Java 异常、内存不足、native 崩溃、ANR 或系统终止，也不能声称本版本已在该用户数据上消除闪退。

代码没有发现“1,000 条”的固定失败阈值。普通封面网络并发原已有上限；条目总数会增加标签补全次数及滚动遍历的导入封面任务/缓存压力，属于放大因素。是否必要触发条件须实测。

## 封面异常：已确认的代码问题与修复

### 导入压缩包离屏后丢图、串图

旧 `DownloadAdapter.loadArchiveThumbnail` 直接调用 `setImageBitmap/setImageResource`，没有进入 `LoadImageView.load` 的状态管理。离屏时 `LoadImageView` 会清图，再入屏可能以空 URL 或前一个项目的 URL 重载；RecyclerView 缓存中的 holder 不一定再次绑定。异步回写也没有检查当前绑定。

现在占位和图片均通过 `load`，回写校验绑定 key，holder 回收时清除 tag 并卸载请求。

证据路径：`app/src/main/java/com/hippo/ehviewer/ui/scene/download/part/DownloadAdapter.kt`、`app/src/main/java/com/hippo/widget/LoadImageView.java`。

### 缺标签补全反复取消封面请求

进入下载页后，`DownloadMetadataLoader` 为缺少原始标签的下载项逐项补全；旧回调对每个成功项触发 `onLocalTagsChanged -> updateForLabel -> rebuildDisplayedList -> notifyDataSetChanged`，可见封面反复清图和取消重载。条目多、标签缺失多、存储/网络慢时尤其值得验证。

现在标签补全仅在标签分组或搜索会受影响时更新列表，普通未过滤列表保留封面请求；手动元数据刷新仍正常更新项目内容。

证据路径：`app/src/main/java/com/hippo/ehviewer/download/DownloadMetadataLoader.java`、`app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.java`。

### 导入封面线程、缓存与压缩包管道

旧相册/压缩包封面每次缓存未命中直接启动线程，无同 key 去重，Bitmap HashMap 无容量上限。压缩包先通过管道读图片尺寸，再对同一 entry 启动第二次提取，第一条管道未确定关闭；第二次 decode 之后的 join 超时无法限制 decode 本身，存在 writer 满缓冲等待及并发访问同一 archive 的风险。具体 native 时序是否造成现场崩溃仍未证实。

现在采用共享两工作线程、32 槽有界队列、同 key 合并、弱引用等待者、8 MiB 字节 LRU；队列满时延迟重试仍绑定的视图。adapter 脱离 RecyclerView 后清理等待通知/缓存，排队工作跳过失效 adapter。已经开始的 native/存储调用仍需等待返回，不能保证即时中断。压缩包首图在同一工作线程完整提取到临时文件，两遍 decodeFile 后 finally 删除；按最长边采样至约 300 像素。临时文件增加磁盘 I/O，底层存储卡死仍需设备诊断。

### 无远程 URL、损坏非空封面

旧 `LoadImageView.load` 在 URL 为空时直接返回，即使本地 `.thumb` 可用；现在允许仅本地/缓存加载，缺 URL 时禁用网络。

旧 `DownloadCover.saveIfMissing` 把任何非空 `.thumb` 当作有效，健康网络响应也可能被损坏旧文件挡住。本版先采样验证旧图，可用旧图仍受到保护；坏文件可由已验证的新图原子替换。

## 刷新相关：已确认问题、关联与未确认原因

旧 `DownloadRefreshDialog.stop` 在主线程等待 worker 提交锁，而 worker 持锁执行 SAF 文件交换、缓存删除和数据库提交。慢存储时，取消或退出下载页可能阻塞主线程，造成卡死/ANR。本版停止标记不再等待该锁；已开始的提交允许完成，提交完成后的详情缓存发布不被取消丢弃，场景回调检查页面有效性。

刷新完成也会重新绑定列表，旧封面任务可能在缓存清除后把旧图重新写入同一个 key。本版下载封面使用提交后递增的缓存版本，晚到旧请求无法命中新封面的 key。进程重启后版本恢复默认，默认 key 在替换时已清除；版本缓存只在进程内使用。

黑封面与退出可能共享资源压力和重绑触发链：反复补标签/重绑导致封面任务积累，单项刷新增加解码/存储提交并再次重绑。但这只是有代码依据的关联假设，缺少现场退出证据，不能认定两者同一根因。取消锁问题仅解释相应条件下的 ANR，不能直接解释所有“点击刷新立即闪退”。

`Image` 引用计数另有历史问题，当前缓存驱逐走 release 而非直接 recycle；本次未贸然改变全局图片所有权，也没有证据支持“刷新必然回收正在显示的 native 图片”这一结论。

## 定位现场所需步骤与关键证据

1. 记录手机型号、Android/ROM、RAM、应用版本/安装来源、可用存储，下载目录类型（内部共享目录/SD 卡/SAF/content URI）。统计普通下载、导入相册、导入压缩包的数量，并说明列表/封面瀑布流模式、分组/筛选和排序。
2. 保留原始数据，先收集日志，再改变缓存或数据。复现前执行 `adb logcat -c`，另一个终端执行 `adb logcat -b all -v threadtime > download-repro-logcat.txt`，覆盖打开下载页、滚动、单项刷新和进程退出全过程，随后停止采集。保留精确时间及点击步骤。
3. 分别识别 `FATAL EXCEPTION`/异常堆栈、`OutOfMemoryError`、`Fatal signal`/tombstone、`ANR in com.kelaner.ehviewer`、`lmkd`/系统终止。必要时采集 `adb bugreport download-bugreport.zip`；Android 11+ 可辅助查看 `adb shell dumpsys activity exit-info com.kelaner.ehviewer`。退出类型决定后续修复方向。
4. 采样进入前、黑封面后、刷新前后的 `adb shell dumpsys meminfo com.kelaner.ehviewer`。可访问时记录该进程线程数和 FD 数，比较是否随滚动/反复进退页单调上涨，退出页面后是否回落。ANR 重点查看主线程是否等待刷新提交 monitor；线程 dump 重点查看旧 Pipe.wait、解压及 SAF 调用。
5. 在测试副本中用相同混合数据比较较小集合与超过 1,000 条集合，控制网络、缓存、分组和存储条件；分别测冷/热缓存、离线/在线、普通/导入项目。避免直接删除用户下载。条目数下降后恢复只是相关证据，还需区分工作集、标签缺失数量与异常文件分布。
6. 抽取黑封面项目：远程 thumb 是否为空、`.thumb` 是否存在/非零/可解码、URI 授权是否仍有效、首图是否长图或损坏；网络封面响应的状态码和内容类型是否正确。导入压缩包测试“初次显示→滚出屏幕→滚回”，观察是否丢图或串图。
7. 对修复版重复相同流程：等待后台补标签、快速滚动、单项及多项刷新、刷新途中取消/退出/旋转、慢 SAF/拒绝授权和损坏图片。必要时临时记录绑定/取消/完成/缓存命中计数，验证同 key 请求不再被无关标签通知反复打断。

提供日志时可删除账号、Cookie、token 和个人路径等私密内容，保留异常类型、堆栈、进程名、时间和存储提供者类型。

## 验证与发布说明

本地使用 JDK 21 与 Android SDK 编译，执行现有下载排序、元数据、封面替换、刷新对话框和布局测试。新增回归用例放入现有测试文件：坏非空封面恢复、无 URL 缓存封面显示、刷新后隔离晚到旧缓存写入、慢存储提交时取消不等待。未连接安卓真机，尚未执行真实千项数据、SAF 和原生解压压力测试。

本机未配置正式 release keystore，因此正式签名包由仓库现有 GitHub Actions 使用既有 signing secrets 构建和验证，不更换签名。发布页应提供 APK、SHA256、此报告以及最终 CI 结果。

需要用户补充的最小信息：当前版本、机型/Android、普通/相册/压缩包条目比例、存储位置、刷新后退出的完整 logcat（以及若存在的 ANR/崩溃堆栈）。在这些信息齐备前，本版定义为修复已确认缺陷，不定义为已经证明并根治现场闪退。
