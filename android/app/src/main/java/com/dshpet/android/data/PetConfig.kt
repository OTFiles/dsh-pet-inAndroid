package com.dshpet.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dshpet")

/**
 * 全局设置存储（DataStore）。对应原桌面端 config.json 的字段子集 +
 * Android 特有项（毛玻璃、隐藏后台、忽略电池优化等）。
 * 多开实例的"位置/朝向"单独存（见 [PetState]），全局设置各实例共享。
 */
class PetConfig(private val ctx: Context) {

    private val ds get() = ctx.dataStore

    companion object {
        fun get(ctx: Context) = PetConfig(ctx)

        // ---- 缩放档位（相对 640 宽，与原 SCALE_STEPS 一致）----
        val SCALE_STEPS = listOf(0.5, 0.72, 0.85, 1.0)

        /** 默认播放速度（手机上 1x 偏慢，默认 1.5x） */
        const val DEFAULT_PLAYBACK_SPEED = 1.5

        private val K_CHARACTER = stringPreferencesKey("character")
        private val K_SCALE = doublePreferencesKey("scale")
        private val K_FACING = stringPreferencesKey("facing")
        private val K_ON_TOP = booleanPreferencesKey("on_top")
        private val K_NO_MOVE = booleanPreferencesKey("no_move")
        private val K_LOCK_POSITION = booleanPreferencesKey("lock_position")
        private val K_SHIFT_DRAG = booleanPreferencesKey("shift_drag")       // Android: 仅长按可拖动
        private val K_DRAG_PHYSICS = booleanPreferencesKey("drag_physics")
        private val K_PET_OPACITY = intPreferencesKey("pet_opacity")
        private val K_PLAYBACK_SPEED = doublePreferencesKey("playback_speed")
        private val K_ANIM_GAP_SEC = doublePreferencesKey("animation_gap_seconds")
        private val K_CLICK_SOUND = booleanPreferencesKey("click_sound_enabled")
        private val K_CLICK_SHOW_BALANCE = booleanPreferencesKey("click_show_balance")
        private val K_CLICK_SHOW_SELF_TALK = booleanPreferencesKey("click_show_self_talk")

        // ---- 自言自语 ----
        private val K_SELF_TALK = booleanPreferencesKey("self_talk_enabled")
        private val K_SELF_TALK_MIN = intPreferencesKey("self_talk_min_interval")
        private val K_SELF_TALK_MAX = intPreferencesKey("self_talk_max_interval")
        private val K_SELF_TALK_DURATION = doublePreferencesKey("self_talk_duration_seconds")
        private val K_SELF_TALK_TEXTS = stringSetPreferencesKey("self_talk_texts")
        private val K_SELF_TALK_BUBBLE_STYLE = stringPreferencesKey("self_talk_bubble_style")

        // ---- 通用 Android 项 ----
        private val K_BLUR = booleanPreferencesKey("blur_enabled")            // 毛玻璃（默认关）
        private val K_HIDE_RECENTS = booleanPreferencesKey("hide_from_recents") // 隐藏后台
        private val K_AUTO_START = booleanPreferencesKey("autostart")          // 开机自启
        private val K_BATTERY_OPT = booleanPreferencesKey("battery_optimization")
        private val K_OVERLAY_PERM = booleanPreferencesKey("overlay_permission_granted")
        private val K_PET_RUNNING = booleanPreferencesKey("pet_running")
        private val K_MOUSE_THROUGH = booleanPreferencesKey("mouse_through") // 桌面端兼容保留（Android 无意义）

        // ---- AI 对话 ----
        private val K_CHAT_NAME = stringPreferencesKey("chat_provider_name")
        private val K_CHAT_BASE_URL = stringPreferencesKey("chat_base_url")
        private val K_CHAT_PATH = stringPreferencesKey("chat_chat_path")
        private val K_CHAT_MODEL = stringPreferencesKey("chat_model")
        private val K_CHAT_API_KEY = stringPreferencesKey("chat_api_key")
        private val K_CHAT_TEMPERATURE = doublePreferencesKey("chat_temperature")
        private val K_CHAT_MAX_TOKENS = intPreferencesKey("chat_max_tokens")
        private val K_CHAT_TIMEOUT = intPreferencesKey("chat_timeout")
        private val K_CHAT_VERIFY_SSL = booleanPreferencesKey("chat_verify_ssl")
        private val K_BALANCE_REFRESH_MIN = intPreferencesKey("balance_refresh_minutes")

        // ---- 快捷启动 ----
        private val K_QUICK_LAUNCH = stringSetPreferencesKey("quick_launch_apps") // "pkg|label"

        // ---- 多开 ----
        private val K_INSTANCE_COUNT = intPreferencesKey("spawned_instance_count")

        const val DEFAULT_CHARACTER = "shenshen"
        const val DEFAULT_SELF_TALK_TEXT =
            "好女孩……好模型……欧鲸鲸……今天也要认真工作呀。再陪你一会儿。"
    }

    // ================ 读取 ================
    private suspend fun <T> read(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): T =
        ds.data.first()[key] ?: default

    suspend fun character() = read(K_CHARACTER, DEFAULT_CHARACTER)
    suspend fun scale() = read(K_SCALE, 0.72)
    suspend fun facing() = read(K_FACING, "left")
    suspend fun onTop() = read(K_ON_TOP, true)
    suspend fun noMove() = read(K_NO_MOVE, false)
    suspend fun lockPosition() = read(K_LOCK_POSITION, false)
    suspend fun shiftDrag() = read(K_SHIFT_DRAG, false)
    suspend fun dragPhysics() = read(K_DRAG_PHYSICS, false)
    suspend fun petOpacity() = read(K_PET_OPACITY, 100).coerceIn(10, 100)
    suspend fun playbackSpeed() = read(K_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED).coerceIn(0.5, 3.0)
    suspend fun animGapSeconds() = read(K_ANIM_GAP_SEC, 0.0).coerceIn(0.0, 3600.0)
    suspend fun clickSound() = read(K_CLICK_SOUND, true)
    suspend fun clickShowBalance() = read(K_CLICK_SHOW_BALANCE, false)
    suspend fun clickShowSelfTalk() = read(K_CLICK_SHOW_SELF_TALK, false)

    suspend fun selfTalkEnabled() = read(K_SELF_TALK, false)
    suspend fun selfTalkMin() = read(K_SELF_TALK_MIN, 20)
    suspend fun selfTalkMax() = read(K_SELF_TALK_MAX, 60)
    suspend fun selfTalkDuration() = read(K_SELF_TALK_DURATION, 3.2)
    suspend fun selfTalkTexts(): Set<String> = read(K_SELF_TALK_TEXTS, defaultSelfTalkTexts())
    suspend fun selfTalkBubbleStyle() = read(K_SELF_TALK_BUBBLE_STYLE, "classic_top")

    suspend fun blurEnabled() = read(K_BLUR, false)
    suspend fun hideFromRecents() = read(K_HIDE_RECENTS, true)
    suspend fun autoStart() = read(K_AUTO_START, false)
    suspend fun batteryOpt() = read(K_BATTERY_OPT, false)
    suspend fun overlayPermissionGranted() = read(K_OVERLAY_PERM, false)
    suspend fun petRunning() = read(K_PET_RUNNING, false)

    suspend fun chatProviderName() = read(K_CHAT_NAME, "DeepSeek")
    suspend fun chatBaseUrl() = read(K_CHAT_BASE_URL, "https://api.deepseek.com")
    suspend fun chatPath() = read(K_CHAT_PATH, "/v1/chat/completions")
    suspend fun chatModel() = read(K_CHAT_MODEL, "deepseek-chat")
    suspend fun chatApiKey() = read(K_CHAT_API_KEY, "")
    suspend fun chatTemperature() = read(K_CHAT_TEMPERATURE, 0.7).coerceIn(0.0, 2.0)
    suspend fun chatMaxTokens() = read(K_CHAT_MAX_TOKENS, 2048)
    suspend fun chatTimeout() = read(K_CHAT_TIMEOUT, 60)
    suspend fun chatVerifySsl() = read(K_CHAT_VERIFY_SSL, true)
    suspend fun balanceRefreshMinutes() = read(K_BALANCE_REFRESH_MIN, 0)

    suspend fun quickLaunch(): List<Pair<String, String>> =
        read(K_QUICK_LAUNCH, emptySet())
            .mapNotNull { it.split("|", limit = 2).let { p -> if (p.size == 2) p[0] to p[1] else null } }

    suspend fun instanceCount() = read(K_INSTANCE_COUNT, 0)

    private fun defaultSelfTalkTexts(): Set<String> =
        setOf("好女孩……", "好模型……", "欧鲸鲸……", "今天也要认真工作呀。", "再陪你一会儿。")

    // ================ 写入 ================
    suspend fun set(key: String, value: Any) {
        ds.edit { prefs ->
            when (value) {
                is Boolean -> prefs[booleanPreferencesKey(key)] = value
                is Int -> prefs[intPreferencesKey(key)] = value
                is Long -> prefs[longPreferencesKey(key)] = value
                is Float -> prefs[floatPreferencesKey(key)] = value
                is Double -> prefs[doublePreferencesKey(key)] = value
                is String -> prefs[stringPreferencesKey(key)] = value
                is Set<*> -> prefs[stringSetPreferencesKey(key)] = value.filterIsInstance<String>().toSet()
            }
        }
    }

    suspend fun setCharacter(v: String) = set("character", v)
    suspend fun setScale(v: Double) = set("scale", v)
    suspend fun setFacing(v: String) = set("facing", v)
    suspend fun setOnTop(v: Boolean) = set("on_top", v)
    suspend fun setNoMove(v: Boolean) = set("no_move", v)
    suspend fun setLockPosition(v: Boolean) = set("lock_position", v)
    suspend fun setShiftDrag(v: Boolean) = set("shift_drag", v)
    suspend fun setDragPhysics(v: Boolean) = set("drag_physics", v)
    suspend fun setPetOpacity(v: Int) = set("pet_opacity", v)
    suspend fun setPlaybackSpeed(v: Double) = set("playback_speed", v)
    suspend fun setAnimGap(v: Double) = set("animation_gap_seconds", v)
    suspend fun setClickSound(v: Boolean) = set("click_sound_enabled", v)
    suspend fun setClickShowBalance(v: Boolean) = set("click_show_balance", v)
    suspend fun setClickShowSelfTalk(v: Boolean) = set("click_show_self_talk", v)
    suspend fun setSelfTalk(v: Boolean) = set("self_talk_enabled", v)
    suspend fun setSelfTalkMin(v: Int) = set("self_talk_min_interval", v)
    suspend fun setSelfTalkMax(v: Int) = set("self_talk_max_interval", v)
    suspend fun setSelfTalkDuration(v: Double) = set("self_talk_duration_seconds", v)
    suspend fun setSelfTalkTexts(v: Set<String>) = set("self_talk_texts", v)
    suspend fun setSelfTalkBubbleStyle(v: String) = set("self_talk_bubble_style", v)
    suspend fun setBlur(v: Boolean) = set("blur_enabled", v)
    suspend fun setHideFromRecents(v: Boolean) = set("hide_from_recents", v)
    suspend fun setAutoStart(v: Boolean) = set("autostart", v)
    suspend fun setBatteryOpt(v: Boolean) = set("battery_optimization", v)
    suspend fun setOverlayPermission(v: Boolean) = set("overlay_permission_granted", v)
    suspend fun setPetRunning(v: Boolean) = set("pet_running", v)

    suspend fun setChatProviderName(v: String) = set("chat_provider_name", v)
    suspend fun setChatBaseUrl(v: String) = set("chat_base_url", v)
    suspend fun setChatPath(v: String) = set("chat_chat_path", v)
    suspend fun setChatModel(v: String) = set("chat_model", v)
    suspend fun setChatApiKey(v: String) = set("chat_api_key", v)
    suspend fun setChatTemperature(v: Double) = set("chat_temperature", v)
    suspend fun setChatMaxTokens(v: Int) = set("chat_max_tokens", v)
    suspend fun setChatTimeout(v: Int) = set("chat_timeout", v)
    suspend fun setChatVerifySsl(v: Boolean) = set("chat_verify_ssl", v)
    suspend fun setBalanceRefreshMinutes(v: Int) = set("balance_refresh_minutes", v)
    suspend fun setQuickLaunch(v: Set<String>) = set("quick_launch_apps", v)

    /** 多开实例计数（仅递增，供"生小肥鱼"分配 instanceId） */
    suspend fun nextInstanceId(): Int {
        val cur = read(K_INSTANCE_COUNT, 0)
        val next = cur + 1
        set("spawned_instance_count", next)
        return next
    }

    // ================ Flow 版本（供 Compose 响应式 UI） ================
    fun <T> flow(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): Flow<T> =
        ds.data.map { it[key] ?: default }

    fun flowBool(key: String, default: Boolean) =
        flow(booleanPreferencesKey(key), default)

    fun flowDouble(key: String, default: Double) =
        flow(doublePreferencesKey(key), default)

    fun flowInt(key: String, default: Int) =
        flow(intPreferencesKey(key), default)

    fun flowString(key: String, default: String) =
        flow(stringPreferencesKey(key), default)

    fun flowStringSet(key: String, default: Set<String>) =
        flow(stringSetPreferencesKey(key), default)

    // ================ 同步读取（协程内） ================
    suspend fun getString(key: String, default: String) =
        ds.data.first()[stringPreferencesKey(key)] ?: default

    suspend fun getBool(key: String, default: Boolean) =
        ds.data.first()[booleanPreferencesKey(key)] ?: default

    suspend fun getInt(key: String, default: Int) =
        ds.data.first()[intPreferencesKey(key)] ?: default

    suspend fun getDouble(key: String, default: Double) =
        ds.data.first()[doublePreferencesKey(key)] ?: default

    suspend fun getStringSet(key: String, default: Set<String>) =
        ds.data.first()[stringSetPreferencesKey(key)] ?: default
}

/**
 * 每只桌宠实例独立的状态（位置/朝向）：多开时互不覆盖。
 * 存于独立 DataStore 文件 pet_state_<instanceId>。
 *
 * DataStore 必须进程内单例：同一文件多个 DataStore 实例会抛
 * "multiple DataStores active"（服务关闭再启动即触发），
 * 故用 companion 缓存按 instanceId 复用。
 */
class PetState(ctx: Context, instanceId: Int) {

    private val ds = storeFor(ctx.applicationContext, instanceId)

    suspend fun load(): State {
        val p = ds.data.first()
        return State(
            x = p[intPreferencesKey("x")] ?: -1,
            y = p[intPreferencesKey("y")] ?: -1,
            facing = p[stringPreferencesKey("facing")] ?: "left",
        )
    }

    suspend fun save(s: State) {
        ds.edit {
            it[intPreferencesKey("x")] = s.x
            it[intPreferencesKey("y")] = s.y
            it[stringPreferencesKey("facing")] = s.facing
        }
    }

    data class State(val x: Int = -1, val y: Int = -1, val facing: String = "left")

    companion object {
        private val stores = java.util.concurrent.ConcurrentHashMap<Int, DataStore<Preferences>>()

        private fun storeFor(ctx: Context, id: Int): DataStore<Preferences> =
            stores.getOrPut(id) {
                PreferenceDataStoreFactory.create {
                    ctx.preferencesDataStoreFile("pet_state_$id")
                }
            }
    }
}
