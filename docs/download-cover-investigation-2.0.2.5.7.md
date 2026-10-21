# 下载封面黑屏修复：2.0.2.5.7

## 分析范围与根因

本次以已有源码、真实本地图片和异步加载行为测试定位问题，不把内存容量不足直接当作结论，也没有扩大内存缓存容量。

1. **共享磁盘缓存回灌的锁升级死锁**：`ConacoTask.putFromDiskCacheToDataContainer()` 原先持有某键的缓存读锁调用 `ThumbDataContainer.save()`。保存调用 `DownloadCover.replace()`，后者使同键缓存失效，需要获取该键写锁。线程等待自己尚未释放的读锁，阻塞唯一 Conaco-Disk 工作线程，并持有 DownloadCover 类锁。内存中最新少量封面仍能命中，其余依赖本地/磁盘读取的项目无法完成；连续新增与内存淘汰会暴露并放大此路径。
2. **缓存输出流未关闭**：`SimpleDiskCache.putToDisk()` 成功路径和 `CacheOutputStreamPipe.close()` 均只提交编辑而未关闭实际输出流。`DiskLruCache.Editor.commit()` 不负责关闭流。大量封面复制/下载造成文件句柄积累，可能使后续本地文件读取失败。
3. **来源优先级不完整**：Conaco 在 `.thumb` 前命中共享内存缓存，并对同键任务排队。本地有效封面可被旧远端图遮蔽或等待慢缓存/网络。原先本地读取成功后还要复制到共享磁盘缓存才交付，离线文件显示不应依赖这一步。
4. **失败与复用边界**：普通列表新异步绑定须立即清掉旧项图；失败后重新附着及手动重试须保留本地来源。导入相册已有但坏掉的 `.thumb` 原先阻断首图回退，最终保留透明占位。

内存淘汰本身不是已证实的直接回收黑屏根因：当前 helper 在缓存淘汰时 release，并非直接 recycle；Image 引用计数另有既存问题。本次不单独改变 Image 的回收条件，以免影响阅读器及尚未配套的 Task/View 所有权。新本地任务完成后注销且不进入共享缓存，视图释放 Drawable 后可正常 GC。

## 修复

- DataContainer 提供默认关闭的本地优先契约，仅下载缩略图启用；其余网络加载默认行为不变。
- 有效 `.thumb` 先在独立、最大并发 2 的本地解码队列读取，不参与同键远程排队，不读写共享缓存，成功直接交付。线程空闲可退出。
- 本地缺失、损坏或不可读，回退内存、共享磁盘、网络。内存回退先显示，同时继续尝试持久化修复；后续修复失败不清掉已经显示的可用图。
- 磁盘回灌先暂存完整数据并释放读锁，再调用容器保存/失效；修复失败不删除可能刚刷新的本地文件。
- 两种缓存写入都先关闭实际文件流再提交编辑，保留既有缓存大小与清理策略。
- 本地任务继续使用取消状态和 taskId 双重校验；普通重绑定同步清旧图；失败重附着和手动重试保留容器、键和 URL。
- 显式刷新仍使用 DownloadCover 验证、原子替换和 revision 隔离；导入相册缓存键也包含 revision，坏 `.thumb` 回退首张图片，全部失败显示失败图。

## 回归验证设计

- `LocalCoverLoadingTest`：真实 Conaco / LoadImageView / ThumbDataContainer / 本地 PNG；本地像素胜过共享缓存、1201 项两轮复用与取消/重附着、实际内存淘汰后像素不变、同键远端和共享磁盘锁阻塞时本地仍显示、坏文件及不可读文件缓存/网络回退、修复后清空共享缓存仍离线显示、离线修复失败保持可用内存图。
- `DownloadCoverRecyclerTest`：真实 RecyclerView + LinearLayoutManager，多 holder，分批新增至 1201，首/中/尾快速滚动与复用，按当前绑定项逐像素核对带身份标记的封面，远程调用为零。
- `SimpleDiskCacheTest`：1201 条数据交替走直接写入/输出管道并逐项读回；可观测 Unix FD 的宿主测量增量且不靠强制 GC 掩盖泄漏。Windows 无 Unix FD 指标时仅跳过该指标测试。
- 保留并执行已有 DownloadCoverTest、DownloadRefreshDialogTest、DownloadThumbLayoutTest，验证封面刷新、坏文件修复、事务失败恢复与布局。新增测试进入 Build CI。

宿主 Windows 的 ImageDecoder 内存映射会阻止同文件重命名，因此原子替换用 API 27 非映射解码路径验证；API 28 原生图像路径仍用于本地优先、取消与压力测试。Android/Linux CI 补充文件句柄指标验证。真实用户现场未接入，不将宿主回归测试等同于用户设备实测。

本地实际执行：封面相关 20 个测试，0 失败，Windows 无 Unix FD 指标的 1 个测试跳过；调试 universal APK 构建通过。另尝试完整单元测试共 110 个，其中 27 个既有数据库/解析/Cookie 测试因宿主找不到 Android `ehviewer` JNI 库而失败（`UnsatisfiedLinkError: no ehviewer in java.library.path`），并非全部测试通过；未为此次修复改写这些无关测试或生产 JNI 初始化。随后按仓库既有 CI 测试集加本次新回归重新执行：共 67 个、0 失败、1 个 Windows FD 指标跳过；单独运行 Lint 发现 8 errors、932 warnings、2 hints，均位于此次未修改的 StageActivity、themes.xml、HackyTextInputEditText、EhDrawerLayout（MissingSuperCall、NewApi、SoonBlockedPrivateApi、RestrictedApi），因此完整 Debug Lint 未通过；未添加 suppress 或降低 abortOnError。签名 Release 使用仓库原有流水线及其 release 检查，不声称完整 Lint 通过。独立生产补丁评审未发现可确认的新增 P1/P2 阻断问题。

## 版本与发布

versionName：2.0.2.5.6 → 2.0.2.5.7；versionCode：117 → 118。发布前要求本地回归/构建通过，并用仓库既有 CI 签名生成 universal APK，校验版本、原签名和四种 ABI 后发布。
