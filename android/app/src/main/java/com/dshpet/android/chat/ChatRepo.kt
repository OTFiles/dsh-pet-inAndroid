package com.dshpet.android.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 聊天会话存储：JSON 文件（对齐桌面端 session_store 的轻量方案，无数据库依赖）。
 * 目录：filesDir/chat_sessions/<id>.json
 */
class ChatRepo(private val ctx: Context) {

    private val dir: File get() = File(ctx.filesDir, "chat_sessions")

    data class Message(val role: String, val content: String, val ts: Long = System.currentTimeMillis())

    data class Session(
        val id: String,
        var title: String,
        val createdAt: Long,
        var updatedAt: Long,
        val messages: MutableList<Message>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    put("ts", m.ts)
                })
            }
            put("messages", arr)
        }

        companion object {
            fun fromJson(o: JSONObject): Session {
                val msgs = mutableListOf<Message>()
                val arr = o.optJSONArray("messages")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        msgs.add(Message(m.optString("role", "user"), m.optString("content", ""), m.optLong("ts")))
                    }
                }
                return Session(
                    id = o.optString("id"),
                    title = o.optString("title", "新对话"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    messages = msgs,
                )
            }
        }
    }

    suspend fun list(): List<Session> = withContext(Dispatchers.IO) {
        val d = dir
        if (!d.exists()) return@withContext emptyList()
        d.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    Session.fromJson(JSONObject(f.readText()))
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    suspend fun get(id: String): Session? = withContext(Dispatchers.IO) {
        val f = File(dir, "$id.json")
        if (!f.exists()) null
        else try { Session.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
    }

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        session.updatedAt = System.currentTimeMillis()
        dir.mkdirs()
        File(dir, "${session.id}.json").writeText(session.toJson().toString())
    }

    suspend fun create(title: String = "新对话"): Session {
        val s = Session(
            id = System.currentTimeMillis().toString() + (0..999).random(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = mutableListOf(),
        )
        save(s)
        return s
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        get(id)?.let { it.title = title; save(it) }
    }

    suspend fun append(id: String, message: Message) = withContext(Dispatchers.IO) {
        get(id)?.let {
            it.messages.add(message)
            save(it)
        }
    }

    /** 会话标题：首条用户消息前 20 字 */
    fun deriveTitle(firstUserText: String): String =
        firstUserText.replace("\n", " ").trim().take(20).ifEmpty { "新对话" }
}
