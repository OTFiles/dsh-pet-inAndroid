package com.dshpet.android.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dshpet.android.util.AppLog
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Agent 联动插件总线（对齐上游 docs/AGENT_LINK_PROTOCOL.md 统一事件协议）。
 *
 * ## 协议
 * 事件文件：/sdcard/dsh-pet/agent-events/<agent>.jsonl（JSON Lines，UTF-8 追加写）
 * 每行一个 JSON 对象：
 *   {"ts": 1750000000.0, "agent": "myagent", "state": "working"}
 *   {"ts": ..., "event": "PreToolUse", "tool": "bash"}   // 事件名按内置映射归一
 *
 * 六态词汇（与上游一致）：
 *   thinking / working / attention / error / idle / sleeping
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
        /** 事件目录（外部 App / Tasker / adb 可写） */
        fun eventsDir(context: Context): File {
            // 首选公共下载旁的专用目录；无权限时退回 app 外部私有目录（adb 可写）
            val public = File(
                android.os.Environment.getExternalStorageDirectory(),
                "dsh-pet/agent-events"
            )
            if (public.isDirectory || public.mkdirs()) return public
            return File(context.getExternalFilesDir(null), "agent-events").apply { mkdirs() }
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

    private class Tailer(val file: File) {
        var offset = 0L
        var inodeStable = true
        val halfLine = StringBuilder()
        var known = false // 是否已越过历史内容（首次只 tail 新增）
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tailers = mutableMapOf<String, Tailer>()
    private var running = false
    private var enabled = true

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                pollOnce()
            } catch (e: Throwable) {
                AppLog.log("PLUGIN", "agent-events 轮询异常: ${e.message}")
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poller)
        AppLog.log("PLUGIN", "AgentEventBus 启动，目录 ${eventsDir(context).path}")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poller)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            tailers.clear()
        } else {
            // 重新启动时全部重新 tail（不回放历史）
            tailers.values.forEach { it.known = false; it.offset = 0; it.halfLine.setLength(0) }
        }
    }

    private fun pollOnce() {
        if (!enabled) return
        val dir = eventsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return
        val seen = mutableSetOf<String>()
        for (f in files) {
            seen.add(f.name)
            val t = tailers.getOrPut(f.name) { Tailer(f).apply { offset = f.length(); known = true } }
            tail(t)
        }
        // 被删除/轮转的文件：重置（新文件同名会重新 tail 新增）
        tailers.keys.retainAll { it in seen }
    }

    private fun tail(t: Tailer) {
        val f = t.file
        if (!f.exists()) return
        val len = f.length()
        if (len < t.offset) {
            // 文件被截断/轮转：重置到末尾
            t.offset = len
            t.halfLine.setLength(0)
            return
        }
        if (len == t.offset) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(t.offset)
            val budget = minOf((len - t.offset).toInt(), READ_BUDGET)
            val buf = ByteArray(budget)
            raf.readFully(buf)
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
            t.offset += budget
        }
    }

    private fun handleLine(line: String) {
        try {
            // 兼容首行 BOM
            val raw = line.trimStart().removePrefix("﻿")
            val obj = JSONObject(raw)
            val agent = obj.optString("agent", "agent")
            // state 优先；否则 event 按映射归一
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
