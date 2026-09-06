package com.dshpet.android.pet

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 余额查询（GET /user/balance），1:1 移植 balance.py。
 */
object Balance {

    /** 余额数值结果（分档动画用） */
    data class BalanceInfo(val total: Double, val text: String)

    /**
     * 余额分档动画（上游 v4.0.4 同款）：
     * 按 ¥20 满额折算已用百分比 p，6 档；p=100 全部用完格外档。
     */
    fun tierIndexFor(total: Double, full: Double = 20.0): Int {
        if (total <= 0) return 5
        val p = ((1.0 - total / full) * 100).coerceIn(0.0, 100.0)
        return if (p >= 100.0) 5 else (p / 20).toInt().coerceIn(0, 4)
    }

    /** 档位 → 动画名（与桌面端 assets/config.jsonc 一致；缺素材自动回退） */
    val TIER_ANIMS = listOf(
        "余额-钱袋满溢",   // 0: p < 20
        "余额-金袋叮当",   // 1: 20 ≤ p < 40
        "余额-钱袋如常",   // 2: 40 ≤ p < 60
        "余额-数金皱眉",   // 3: 60 ≤ p < 80
        "余额-袋空如洗",   // 4: 80 ≤ p < 100
        "余额-分文不剩",   // 5: p = 100
    )

    fun fetch(
        baseUrl: String,
        apiKey: String,
        verifySsl: Boolean = true,
        timeoutSec: Int = 10,
    ): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("未配置 API Key"))
        val endpoint = baseUrl.trim().trimEnd('/') + "/user/balance"
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .apply {
                    if (!verifySsl) {
                        // 跳过证书校验（本地网关/自签名），与桌面端 verify_ssl=false 一致
                        val trustAll = arrayOfNulls<java.security.cert.X509Certificate>(0)
                        val tm = object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            @Suppress("UNCHECKED_CAST")
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                                trustAll as Array<java.security.cert.X509Certificate>
                        }
                        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
                        sslCtx.init(null, arrayOf(tm), java.security.SecureRandom())
                        sslSocketFactory(sslCtx.socketFactory, tm)
                        hostnameVerifier { _, _ -> true }
                    }
                }
                .build()
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${resp.code}（该端点可能不支持余额查询）"))
                }
                val text = resp.body?.string().orEmpty()
                val json = org.json.JSONObject(text)
                val infos = json.optJSONArray("balance_infos")
                    ?: return Result.failure(IllegalStateException("响应中没有余额信息"))
                val info = infos.getJSONObject(0)
                val total = info.optString("total_balance", "")
                val granted = info.optString("granted_balance", "")
                val topped = info.optString("topped_up_balance", "")
                if (total.isBlank()) return Result.success("余额信息为空")
                if (granted.isNotBlank() && topped.isNotBlank()) {
                    Result.success("余额 ¥$total（充值 ¥$topped / 赠送 ¥$granted）")
                } else {
                    Result.success("余额 ¥$total")
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
