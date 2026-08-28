package com.dshpet.android.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天界面状态管理：会话列表、当前会话、流式输出。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ChatRepo(app)
    private val config = PetConfig.get(app)

    private val _sessions = MutableStateFlow<List<ChatRepo.Session>>(emptyList())
    val sessions: StateFlow<List<ChatRepo.Session>> = _sessions.asStateFlow()

    private val _current = MutableStateFlow<ChatRepo.Session?>(null)
    val current: StateFlow<ChatRepo.Session?> = _current.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var streamJob: Job? = null

    init {
        refresh()
        ensureSession()
    }

    fun refresh() {
        viewModelScope.launch {
            _sessions.value = repo.list()
        }
    }

    private suspend fun ensureSession() {
        if (_current.value == null) {
            val list = repo.list()
            _current.value = list.firstOrNull() ?: repo.create()
            refresh()
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val s = repo.create()
            _current.value = s
            refresh()
        }
    }

    fun selectSession(id: String) {
        viewModelScope.launch {
            _current.value = repo.get(id)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            if (_current.value?.id == id) {
                val list = repo.list()
                _current.value = list.firstOrNull() ?: repo.create()
            }
            refresh()
        }
    }

    /** 当前会话的消息 + 正在流式的增量 */
    fun displayedMessages(): List<ChatRepo.Message> {
        val base = _current.value?.messages?.toList() ?: emptyList()
        val st = _streamText.value
        return if (st.isNotEmpty()) base + ChatRepo.Message("assistant", st) else base
    }

    fun send(text: String) {
        val session = _current.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        viewModelScope.launch {
            if (session.messages.none { it.role == "user" }) {
                repo.rename(session.id, repo.deriveTitle(trimmed))
            }
            val userMsg = ChatRepo.Message("user", trimmed)
            session.messages.add(userMsg)
            repo.save(session)
            refresh()

            _busy.value = true
            _streaming.value = true
            _streamText.value = ""

            val cfg = SseClient.ChatCfg(
                baseUrl = config.chatBaseUrl(),
                chatPath = config.chatPath(),
                model = config.chatModel(),
                apiKey = config.chatApiKey(),
                temperature = config.chatTemperature(),
                maxTokens = config.chatMaxTokens(),
                timeoutSec = config.chatTimeout(),
                verifySsl = config.chatVerifySsl(),
            )
            val history = session.messages.takeLast(20).map { SseClient.Msg(it.role, it.content) }
            streamJob = SseClient.stream(
                cfg = cfg,
                messages = history,
                onDelta = { _streamText.value += it },
                onError = { err ->
                    _streamText.value = if (_streamText.value.isBlank()) "（错误）$err" else _streamText.value + "\n\n（错误）$err"
                },
                onDone = {
                    val full = _streamText.value
                    if (full.isNotBlank()) {
                        session.messages.add(ChatRepo.Message("assistant", full))
                    }
                    repoSave(session)
                    _streamText.value = ""
                    _streaming.value = false
                    _busy.value = false
                },
            )
        }
    }

    fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        _streaming.value = false
        _busy.value = false
        val s = _current.value
        if (s != null) {
            val full = _streamText.value
            if (full.isNotBlank()) s.messages.add(ChatRepo.Message("assistant", full))
            repoSave(s)
        }
        _streamText.value = ""
    }

    private fun repoSave(session: ChatRepo.Session) {
        viewModelScope.launch {
            repo.save(session)
            refresh()
        }
    }
}
