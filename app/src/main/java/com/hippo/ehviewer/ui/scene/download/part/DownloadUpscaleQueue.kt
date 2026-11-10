package com.hippo.ehviewer.ui.scene.download.part

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.upscale.UpscaleConfig
import com.hippo.ehviewer.upscale.UpscaleStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** A single persisted queue. All state is owned by the main thread. */
object DownloadUpscaleQueue {
    private const val PREFS = "download_upscale_queue"
    private const val KEY = "state"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private data class Item(val gid: Long, val pages: Int, var next: Int = 0, val config: UpscaleConfig)
    private val items = mutableListOf<Item>()
    private var loaded = false
    private var running = false
    private var completed = 0
    private var skipped = 0
    private var rejected = 0
    private var storageError = false
    private var loadError = false
    private var waiting = false
    private var enginePaused = false
    private var missing = 0
    private var userSkipped = 0
    private var removed = 0
    private var pausedMissing = false
    private var failed = 0
    private var lastError = ""

    private fun JSONObject.integer(key: String, default: Long? = null): Long {
        if (!has(key) && default != null) return default
        val value = get(key)
        require(value is Int || value is Long) { "字段 $key 必须是整数" }
        return (value as Number).toLong()
    }

    private fun JSONObject.count(key: String): Int {
        val value = integer(key, 0)
        require(value in 0..Int.MAX_VALUE.toLong()) { "字段 $key 超出范围" }
        return value.toInt()
    }

    private fun JSONObject.flag(key: String): Boolean {
        if (!has(key)) return false
        val value = get(key)
        require(value is Boolean) { "字段 $key 必须是布尔值" }
        return value
    }

    private fun resumeText(root: JSONObject, key: String): String {
        if (!root.has(key)) return ""
        val value = root.get(key)
        require(value is String) { "字段 $key 必须是字符串" }
        return value
    }

    fun resume(context: Context) {
        val app = context.applicationContext
        if (!loaded) {
            loaded = true
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val root = if (!prefs.contains(KEY)) JSONObject().put("items", JSONArray())
                    else JSONObject(prefs.getString(KEY, null) ?: throw IllegalStateException("队列记录为空"))
                completed = root.count("completed")
                skipped = root.count("skipped")
                rejected = root.count("rejected")
                missing = root.count("missing")
                userSkipped = root.count("userSkipped")
                removed = root.count("removed")
                pausedMissing = root.flag("pausedMissing")
                failed = root.count("failed")
                lastError = resumeText(root, "lastError")
                enginePaused = root.flag("enginePaused")
                require(!pausedMissing || enginePaused) { "缺页状态与暂停状态不一致" }
                val saved = root.getJSONArray("items")
                for (i in 0 until saved.length()) {
                    val item = saved.getJSONObject(i)
                    val gid = item.integer("gid")
                    val pagesValue = item.integer("pages")
                    val nextValue = item.integer("next")
                    require(pagesValue in 1..Int.MAX_VALUE.toLong() && nextValue in 0..pagesValue)
                    val pages = pagesValue.toInt()
                    val next = nextValue.toInt()
                    require(gid > 0 && pages > 0 && next in 0..pages && items.none { it.gid == gid })
                    val config = if (item.has("config")) UpscaleConfig.fromJson(item.getJSONObject("config")) else UpscaleConfig.from(app)
                    items.add(Item(gid, pages, next, config))
                }
            } catch (e: Exception) {
                items.clear()
                storageError = true
                loadError = true
                lastError = "队列进度读取失败，已暂停以保护原有进度：${e.message}"
            }
        }
        runNext(app)
    }

    fun enqueue(context: Context, selected: List<DownloadInfo>): Int {
        val app = context.applicationContext
        resume(app)
        if (storageError) return -1
        var added = 0
        val config = UpscaleConfig.from(app)
        for (info in selected) {
            if (items.any { it.gid == info.gid }) continue
            if (info.state != DownloadInfo.STATE_FINISH || info.pages <= 0) {
                rejected++
                lastError = "画廊 ${info.gid} 未完成下载或缺少页数，未加入队列"
                continue
            }
            items.add(Item(info.gid, info.pages, config = config))
            added++
        }
        save(app)
        runNext(app)
        return if (storageError) -1 else added
    }

    @JvmStatic
    fun showStatus(context: Context, prefix: String = "") {
        resume(context)
        val builder = AlertDialog.Builder(context)
            .setTitle("漫画超分队列")
            .setView(queueView(context, prefix))
            .setPositiveButton(android.R.string.ok, null)
        if (canRetry()) builder.setNeutralButton("继续 / 重试") { _, _ ->
            retry(context)
            showStatus(context)
        } else if ((running || waiting || items.isNotEmpty()) && !storageError) {
            builder.setNeutralButton("暂停") { _, _ ->
                enginePaused = true
                lastError = "用户暂停"
                save(context.applicationContext)
                showStatus(context)
            }
        }
        if (enginePaused && !running && !waiting && !storageError && items.isNotEmpty()) {
            builder.setNegativeButton("更多操作") { _, _ ->
                val item = items.first()
                AlertDialog.Builder(context)
                    .setTitle("画廊 ${item.gid}，第 ${item.next + 1} 页")
                    .setItems(arrayOf("跳过此页", "移除当前漫画")) { _, choice ->
                        val action = if (choice == 0) "跳过此页" else "移除当前漫画"
                        AlertDialog.Builder(context)
                            .setMessage("确定${action}？此操作会改变队列进度，不会删除原图或超分结果。")
                            .setNegativeButton(android.R.string.cancel) { _, _ -> showStatus(context) }
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                if (enginePaused && !storageError && items.firstOrNull() === item) {
                                    if (choice == 0) {
                                        if (pausedMissing) missing++ else userSkipped++
                                        item.next++
                                    } else {
                                        items.removeAt(0)
                                        removed++
                                    }
                                    pausedMissing = false
                                    enginePaused = false
                                    save(context.applicationContext)
                                    runNext(context.applicationContext)
                                }
                                showStatus(context)
                            }.show()
                    }.show()
            }
        }
        builder.show()
    }

    private fun queueView(context: Context, prefix: String): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(12))
        }
        TextView(context).apply {
            text = prefix + status()
            setTextIsSelectable(true)
            content.addView(this)
        }
        items.forEachIndexed { index, item ->
            val info = EhApplication.getDownloadManager(context).getDownloadInfo(item.gid)
            TextView(context).apply {
                text = "${if (index == 0 && (running || waiting)) "处理中 · " else ""}${info?.title ?: "画廊 ${item.gid}"}\n${item.next}/${item.pages} 页 · ${item.config.displayName()}"
                setTypeface(typeface, if (index == 0) Typeface.BOLD else Typeface.NORMAL)
                setPadding(0, dp(14), 0, dp(4))
                content.addView(this)
            }
            content.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = item.pages
                progress = item.next
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
        }
        return ScrollView(context).apply { addView(content) }
    }

    private fun thenMarker() = "处理中 · "

    fun canRetry(): Boolean = enginePaused || storageError

    fun retry(context: Context) {
        val app = context.applicationContext
        if (loadError) {
            loaded = false
            loadError = false
            storageError = false
            resume(app)
            return
        }
        if (!canRetry()) return
        storageError = false
        enginePaused = false
        pausedMissing = false
        save(app)
        runNext(app)
    }

    fun status(): String {
        val remaining = items.sumOf { (it.pages - it.next).coerceAtLeast(0) }
        val state = if (storageError) "持久化异常，已暂停" else if (enginePaused && running) "正在完成当前页，随后暂停" else if (enginePaused) "已暂停" else if (waiting) "等待其他任务" else if (running) "处理中" else if (remaining > 0) "等待恢复" else "已结束"
        return "$state：剩余 $remaining 页，成功 $completed 页，已有超分跳过 $skipped 页，确认跳过缺失 $missing 页，手动跳过 $userSkipped 页，失败 $failed 页；移除 $removed 本，未加入 $rejected 本" +
            if (lastError.isNotEmpty()) "\n最近错误：$lastError" else ""
    }

    private fun save(context: Context) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("gid", it.gid).put("pages", it.pages).put("next", it.next).put("config", it.config.toJson())) }
        val root = JSONObject().put("items", array).put("completed", completed).put("skipped", skipped)
            .put("rejected", rejected).put("missing", missing).put("failed", failed).put("lastError", lastError)
            .put("enginePaused", enginePaused).put("pausedMissing", pausedMissing)
            .put("userSkipped", userSkipped).put("removed", removed)
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).commit()) {
            storageError = true
            lastError = "队列进度保存失败，已暂停；请从下载页菜单重试保存"
            Toast.makeText(context, lastError, Toast.LENGTH_LONG).show()
        }
    }

    private fun runNext(context: Context) {
        if (running || waiting || storageError || enginePaused || items.isEmpty()) return
        val item = items.first()
        if (item.next >= item.pages) {
            items.removeAt(0)
            save(context)
            runNext(context)
            return
        }
        val info = EhApplication.getDownloadManager(context).getDownloadInfo(item.gid)
        if (info == null || info.state != DownloadInfo.STATE_FINISH) {
            pause(context, "画廊 ${item.gid} 已不在下载列表或下载状态不再为完成")
            return
        }
        running = true
        val page = item.next
        worker.execute {
            try {
                val status = UpscaleStore.getPageStatus(context, info, page)
                val file = if (status == UpscaleStore.PageStatus.ABSENT) UpscaleStore.getCurrentSource(context, info, page) else null
                main.post {
                    if (status == UpscaleStore.PageStatus.VALID) {
                        skipped++
                        finishPage(context, item)
                    } else if (status != UpscaleStore.PageStatus.ABSENT) {
                        pause(context, "画廊 ${item.gid} 第 ${page + 1} 页的超分记录损坏或无法验证，请检查后重试")
                    } else if (file == null) {
                        pause(context, "画廊 ${item.gid} 第 ${page + 1} 页原图缺失，请恢复后重试或选择跳过", true)
                    } else {
                        try {
                            UpscaleStore.requestPage(context, info, page, file, item.config) { success, message ->
                                when {
                                    success -> completed++
                                    message.contains("该页已有超分结果") -> {
                                        running = false
                                        runNext(context)
                                        return@requestPage
                                    }
                                    message == "该页面正在处理" -> {
                                        running = false
                                        waiting = true
                                        main.postDelayed({
                                            waiting = false
                                            runNext(context)
                                        }, 2000)
                                        return@requestPage
                                    }
                                    else -> {
                                        pause(context, "画廊 ${item.gid} 第 ${page + 1} 页：$message")
                                        return@requestPage
                                    }
                                }
                                finishPage(context, item)
                            }
                        } catch (e: Exception) {
                            pause(context, "画廊 ${item.gid} 第 ${page + 1} 页：${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    pause(context, "画廊 ${item.gid} 第 ${page + 1} 页读取失败：${e.message}")
                }
            }
        }
    }

    private fun pause(context: Context, message: String, isMissing: Boolean = false) {
        pausedMissing = isMissing
        running = false
        enginePaused = true
        lastError = message
        save(context)
        Toast.makeText(context, "超分队列已暂停：$message", Toast.LENGTH_LONG).show()
    }

    private fun finishPage(context: Context, item: Item) {
        item.next++
        running = false
        save(context)
        runNext(context)
    }
}
