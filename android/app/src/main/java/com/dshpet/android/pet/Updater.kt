package com.dshpet.android.pet

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 更新检查（移植 updater.py 的核心判断）。
 */
object Updater {

    private const val REPO = "OTFiles/dsh-pet-inAndroid"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    data class Release(val tag: String, val url: String)

    fun latestRelease(timeoutSec: Int = 10): Result<Release> = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return Result.failure(IllegalStateException("无法连接更新服务（HTTP ${resp.code}）"))
            }
            val json = org.json.JSONObject(resp.body?.string().orEmpty())
            val tag = json.optString("tag_name", "")
            if (tag.isBlank()) return Result.failure(IllegalStateException("无法连接更新服务，请稍后重试"))
            Result.success(Release(tag, json.optString("html_url", RELEASES_URL)))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** "v4.1.0" > "4.0.1" 这类 tag 比较 */
    fun isNewer(tag: String, current: String): Boolean {
        fun parse(v: String): List<Int> =
            v.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        val a = parse(tag)
        val b = parse(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
