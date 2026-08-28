package com.dshpet.android.pet

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 余额查询（GET /user/balance），1:1 移植 balance.py。
 */
object Balance {

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
                        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
                        sslCtx.init(
                            null,
                            arrayOf(object : javax.net.ssl.X509TrustManager {
                                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = trustAll
                            }),
                            java.security.SecureRandom(),
                        )
                        sslSocketFactory(sslCtx.socketFactory, object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = trustAll
                        })
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
