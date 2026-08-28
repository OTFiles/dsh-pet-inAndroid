package com.dshpet.android.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI 兼容 SSE 流式客户端 —— 1:1 移植 providers.py 的
 * normalize_chat_endpoint / SSEParser / OpenAICompatibleProvider.stream。
 */
object SseClient {

    data class ChatCfg(
        val baseUrl: String,
        val chatPath: String,
        val model: String,
        val apiKey: String,
        val temperature: Double,
        val maxTokens: Int,
        val timeoutSec: Int,
        val verifySsl: Boolean,
    )

    data class Msg(val role: String, val content: String)

    fun normalizeEndpoint(baseUrl: String, chatPath: String = "/v1/chat/completions"): String {
        val base = baseUrl.trim().trimEnd('/')
        val path = chatPath.trim().let { if (it.startsWith("/")) it else "/$it" }
        if (base.endsWith("/chat/completions")) return base
        // base 已带版本段（/v1、/v4 等）→ 只补 /chat/completions
        if (path == "/v1/chat/completions" && Regex("/v\\d+$").containsMatchIn(base)) {
            return base + "/chat/completions"
        }
        return base + path
    }

    /**
     * 流式请求。onDelta 在主线程回调；返回的 Job 可 cancel()。
     */
    fun stream(
        cfg: ChatCfg,
        messages: List<Msg>,
        onDelta: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ): Job {
        val cancelled = AtomicBoolean(false)
        return kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = normalizeEndpoint(cfg.baseUrl, cfg.chatPath)
                val payload = JSONObject().apply {
                    put("model", cfg.model)
                    val arr = JSONArray()
                    messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
                    put("messages", arr)
                    put("stream", true)
                    put("temperature", cfg.temperature)
                    put("max_tokens", cfg.maxTokens)
                }
                val client = buildClient(cfg)
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Accept", "text/event-stream")
                    .apply {
                        if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}")
                    }
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string().orEmpty()
                    val code = resp.code
                    val msg = safeErrorDetail(detail)
                    withContext(Dispatchers.Main) {
                        onError(if (code in setOf(401, 403)) "认证失败（HTTP $code）：$msg"
                        else "请求失败（HTTP $code）：$msg")
                    }
                    return@launch
                }
                val source = resp.body?.source() ?: run {
                    withContext(Dispatchers.Main) { onError("网络连接失败") }
                    return@launch
                }
                val parser = SseParser()
                while (!cancelled.get()) {
                    val line = source.readUtf8Line() ?: break
                    for (delta in parser.feedLine(line)) {
                        if (cancelled.get()) return@launch
                        withContext(Dispatchers.Main) { onDelta(delta) }
                    }
                    if (parser.done) break
                }
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                val msg = when (e) {
                    is javax.net.ssl.SSLException, is java.security.cert.CertificateException ->
                        "TLS 证书校验失败：${e.message}；可在 AI 设置中勾选\"跳过 SSL 证书验证\"后重试"
                    is java.net.UnknownHostException -> "网络连接失败：无法解析主机"
                    else -> "网络请求失败：${e.message}"
                }
                withContext(Dispatchers.Main) { onError(msg) }
            }
        }
    }

    /** 连接测试（移植 test_connection）：最小非流式请求 */
    fun testConnection(cfg: ChatCfg, onResult: (Result<String>) -> Unit) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = normalizeEndpoint(cfg.baseUrl, cfg.chatPath)
                val payload = JSONObject().apply {
                    put("model", cfg.model)
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    put("max_tokens", 1)
                    put("stream", false)
                }
                val client = buildClient(cfg)
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .apply {
                        if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}")
                    }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        withContext(Dispatchers.Main) { onResult(Result.success("连接成功（HTTP ${resp.code}）")) }
                    } else {
                        val detail = safeErrorDetail(resp.body?.string().orEmpty())
                        val msg = if (resp.code in setOf(401, 403)) "认证失败（HTTP ${resp.code}）：$detail"
                        else "请求失败（HTTP ${resp.code}）：$detail"
                        withContext(Dispatchers.Main) { onResult(Result.failure(IllegalStateException(msg))) }
                    }
                }
            } catch (e: Exception) {
                val msg = when (e) {
                    is javax.net.ssl.SSLException, is java.security.cert.CertificateException ->
                        "TLS 证书校验失败；可在 AI 设置中勾选\"跳过 SSL 证书验证\"后重试"
                    is java.net.UnknownHostException -> "网络连接失败：${e.message}"
                    else -> "网络连接失败：${e.message}"
                }
                withContext(Dispatchers.Main) { onResult(Result.failure(IllegalStateException(msg))) }
            }
        }
    }

    private fun buildClient(cfg: ChatCfg): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 流式：不设读超时，由连接层控制
            .writeTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!cfg.verifySsl) {
            val trustAll = arrayOfNulls<java.security.cert.X509Certificate>(0)
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = trustAll
            }
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf(tm), java.security.SecureRandom())
            builder.sslSocketFactory(sslCtx.socketFactory, tm)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun safeErrorDetail(raw: String): String {
        if (raw.isBlank()) return "无响应内容"
        return try {
            val o = JSONObject(raw)
            val err = o.opt("error")
            if (err is JSONObject) {
                err.optString("message", raw.take(200))
            } else {
                raw.take(200)
            }
        } catch (e: Exception) {
            raw.take(200)
        }
    }

    /** SSE 事件解析（逐行 feed），1:1 移植 SSEParser */
    class SseParser {
        private val lines = mutableListOf<String>()
        var done = false
            private set

        fun feedLine(line: String?): List<String> {
            if (line == null) return emptyList()
            val out = mutableListOf<String>()
            if (line.isBlank()) {
                // 空行 = 事件结束
                if (lines.isNotEmpty()) {
                    val data = lines
                        .filter { it.isNotEmpty() && !it.startsWith(":") && it.startsWith("data:") }
                        .map { it.removePrefix("data:").trimStart() }
                        .joinToString("\n")
                    lines.clear()
                    if (data.isNotEmpty()) {
                        if (data == "[DONE]") {
                            done = true
                        } else {
                            try {
                                val payload = JSONObject(data)
                                if (payload.has("error")) {
                                    val err = payload.get("error")
                                    val msg = if (err is JSONObject) err.optString("message", "Provider 返回错误") else err.toString()
                                    throw IllegalStateException(msg)
                                }
                                val choices = payload.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content")
                                    if (!content.isNullOrEmpty()) out.add(content)
                                }
                            } catch (e: org.json.JSONException) {
                                throw IllegalStateException("Provider 返回了无效 JSON", e)
                            }
                        }
                    }
                }
            } else {
                lines.add(line)
            }
            return out
        }
    }
}
