package com.dshpet.android.plugin

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.dshpet.android.util.AppLog
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Agent 联动事件总线（对齐上游 docs/AGENT_LINK_PROTOCOL.md 统一事件协议）。
 *
 * ## 协议
 * 事件目录内每个 `<agent>.jsonl`，每行一个 JSON：
 *   {"ts": 1750000000.0, "agent": "myagent", "state": "working"}
 *   {"ts": ..., "event": "PreToolUse", "tool": "bash"}   // 事件名按内置映射归一
 *
 * ## 目录来源（Android 存储限制）
 * Android 11+ 用 File API 读共享存储的非媒体文件需要 MANAGE_EXTERNAL_STORAGE，
 * 或走 SAF 持久授权目录。本实现三种来源按优先级：
 *  1. SAF 用户选择的事件目录（Settings → AI → 选择事件目录），持久 URI 授权
 *  2. MANAGE_EXTERNAL_STORAGE 已授权 → /sdcard/dsh-pet/agent-events
 *  3. app 外部私有目录（adb/root 可写；无需任何权限）
 *
 * ## 实现
 * - byte-offset 增量 tail（1.5s 轮询），不回放历史；半行缓冲不丢事件；
 * - 文件不存在静默空转；单次读取有界 64KB；
 * - 读方只读不写，绝不影响写方。
 */
class AgentEventBus(
    private val context: Context,
    private val onState: (agent: String, state: String) -> Unit,
) {
    companion object {
        /** SAF 持久授权的目录（用户选择过的） */
        @Volatile
        var treeUri: Uri? = null

        /** 公共路径（MANAGE_EXTERNAL_STORAGE 授权时可用） */
        fun publicDir(): File = File(
            android.os.Environment.getExternalStorageDirectory(), "dsh-pet/agent-events"
        )

        /** app 私有外部目录（永远可写，adb/root 可访问） */
        fun fallbackDir(context: Context): File =
            File(context.getExternalFilesDir(null), "agent-events").apply { mkdirs() }

        /** 当前生效目录的可读描述（用于 UI 展示/日志） */
        fun describeSource(context: Context): String = when {
            treeUri != null -> "SAF 目录：${treeUri}"
            android.os.Environment.isExternalStorageManager() ->
                "公共目录：${publicDir().path}"
            else -> "私有目录：${fallbackDir(context).path}（可用 adb/root 写入）"
        }

        fun canAccessPublic(): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.os.Environment.isExternalStorageManager()
            } else {
                // Android 10 及以下：READ_EXTERNAL_STORAGE 已声明（manifest maxSdk=32）
                true
            }

        /** 事件名 → 状态映射（与上游 §2.4 一致；未知事件忽略） */
        private val EVENT_MAP = mapOf(
            "SessionStart" to "idle",
            "SessionEnd" to "idle",
            "UserPromptSubmit" to "thinking",
            "PreToolUse" to "working",
            "PostToolUse" to "working",
            "PostToolUseFailure" to "error",
            "Stop" to "attention",
            "SubagentStop" to "attention",
            "StopFailure" to "error",
            "error" to "error",
            "idle" to "idle",
            "thinking" to "thinking",
            "working" to "working",
            "attention" to "attention",
            "sleeping" to "sleeping",
        )

        private const val POLL_MS = 1500L
        private const val READ_BUDGET = 64 * 1024
        private val VALID_STATES = setOf(
            "thinking", "working", "attention", "error", "idle", "sleeping"
        )
    }

    /** 一个正在 tail 的事件源（File 或 SAF 文档） */
    private class Tailer(val key: String) {
        var offset = 0L
        var initialized = false
        val halfLine = StringBuilder()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tailers = mutableMapOf<String, Tailer>()
    private var running = false
    private var enabled = true

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                pollOnce()
            } catch (e: Throwable) {
                AppLog.log("PLUGIN", "agent-events 轮询异常: ${e.javaClass.simpleName}: ${e.message}")
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poller)
        AppLog.log("PLUGIN", "AgentEventBus 启动，事件源 ${describeSource(context)}")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poller)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) tailers.clear()
    }

    // ================================================================ 轮询
    private fun pollOnce() {
        if (!enabled) return
        val uri = treeUri
        if (uri != null) {
            pollSaf(uri)
        } else if (canAccessPublic()) {
            pollFiles(publicDir())
        } else {
            pollFiles(fallbackDir(context))
        }
    }

    // ---- 普通目录（MANAGE_EXTERNAL_STORAGE 或私有目录）----
    private fun pollFiles(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return
        for (f in files) {
            val key = "file:${f.absolutePath}"
            val t = tailers.getOrPut(key) {
                Tailer(key).apply { offset = f.length(); initialized = true }
            }
            readFiles(f, t)
        }
        // 已删除文件：移除 tailer
        tailers.keys.removeAll { it.startsWith("file:") && !File(it.removePrefix("file:")).exists() }
    }

    private fun readFiles(f: File, t: Tailer) {
        val len = f.length()
        if (len < t.offset) { t.offset = len; t.halfLine.setLength(0); return }
        if (len == t.offset) return
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(t.offset)
                val budget = minOf((len - t.offset).toInt(), READ_BUDGET)
                val buf = ByteArray(budget)
                raf.readFully(buf)
                t.offset += budget
                consume(buf, t)
            }
        }.onFailure { AppLog.log("PLUGIN", "读取 ${f.name} 失败: ${it.message}") }
    }

    // ---- SAF 文档目录 ----
    private fun pollSaf(tree: Uri) {
        val children = documentChildren(tree) ?: return
        for ((name, docUri) in children) {
            if (!name.endsWith(".jsonl")) continue
            val key = "saf:$docUri"
            val t = tailers.getOrPut(key) { Tailer(key) }
            readSaf(docUri, name, t)
        }
    }

    /** 列出 SAF 目录下的 jsonl 文档 */
    private fun documentChildren(tree: Uri): List<Pair<String, Uri>>? {
        return runCatching {
            val treeDocId = DocumentsContract.getTreeDocumentId(tree)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(dirUri)
            )
            val out = mutableListOf<Pair<String, Uri>>()
            context.contentResolver.query(
                childUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out.add(name to DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
            out
        }.getOrElse {
            AppLog.log("PLUGIN", "SAF 目录枚举失败: ${it.message}")
            null
        }
    }

    private fun readSaf(doc: Uri, name: String, t: Tailer) {
        runCatching {
            val input = context.contentResolver.openInputStream(doc) ?: return@runCatching
            input.use { ins ->
                if (!t.initialized) {
                    // 首次：跳过全部历史（只 tail 新增）
                    val buf = ByteArray(READ_BUDGET)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    t.offset = total
                    t.initialized = true
                    return@use
                }
                // 跳过已读
                var skip = t.offset
                val skipBuf = ByteArray(READ_BUDGET)
                while (skip > 0) {
                    val want = minOf(skip, skipBuf.size.toLong()).toInt()
                    val n = ins.read(skipBuf, 0, want)
                    if (n <= 0) return@use
                    skip -= n
                }
                // 读新增（有界）
                var read = 0
                val buf = ByteArray(READ_BUDGET)
                while (read < READ_BUDGET) {
                    val n = ins.read(buf, 0, minOf(READ_BUDGET - read, buf.size))
                    if (n <= 0) break
                    consume(buf.copyOf(n), t)
                    read += n
                }
                t.offset += read
            }
        }.onFailure {
            AppLog.log("PLUGIN", "SAF 读取 $name 失败: ${it.message}")
        }
    }

    // ---- 行解析 ----
    private fun consume(buf: ByteArray, t: Tailer) {
        var start = 0
        for (i in buf.indices) {
            if (buf[i] == '\n'.code.toByte()) {
                val line = t.halfLine.toString() + String(buf, start, i - start, Charsets.UTF_8)
                t.halfLine.setLength(0)
                if (line.isNotBlank()) handleLine(line)
                start = i + 1
            }
        }
        if (start < buf.size) {
            t.halfLine.append(String(buf, start, buf.size - start, Charsets.UTF_8))
        }
    }

    private fun handleLine(line: String) {
        try {
            val raw = line.trimStart().removePrefix("\uFEFF")
            val obj = JSONObject(raw)
            val agent = obj.optString("agent", "agent")
            val state = obj.optString("state", "").ifEmpty {
                val ev = obj.optString("event", "")
                EVENT_MAP[ev] ?: return
            }
            if (state !in VALID_STATES) return
            onState(agent, state)
        } catch (e: Exception) {
            // 坏行忽略，绝不影响总线
        }
    }
}
