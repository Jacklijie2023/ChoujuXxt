package `fun`.kirari.hanako.overlay

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import `fun`.kirari.hanako.debug.AppDebugLogStore
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TTS 语音合成辅助类。
 *
 * 聊天页与悬浮球共用同一引擎（[speak] 用 QUEUE_FLUSH 即时打断旧朗读）。
 * 新增朗读状态（[readingMessageId]）与右上角自动朗读开关（[autoReadEnabled]），
 * 供聊天页消息底部喇叭动画与顶部开关共享：同一时间只有一条消息处于朗读态。
 *
 * 朗读语音：引擎就绪后通过 [availableVoices] 暴露可用语音（不同人物/引擎音色），
 * [setVoice] 切换并即时生效；引擎未就绪时排队，就绪后自动应用。
 */
internal object TtsHelper : TextToSpeech.OnInitListener {
    private const val TAG = "HanakoTTS"
    private var tts: TextToSpeech? = null
    private var _isReady = false
    private var pendingText: String? = null
    private var pendingMessageId: String? = null
    private var pendingVoiceName: String? = null

    /** 可用朗读语音信息（引擎 voices 的展示形态）。 */
    data class TtsVoiceInfo(
        val name: String,
        val displayName: String,
        val locale: String
    )

    /** 当前选中的朗读语音名称（[setVoice] 记录，引擎就绪后应用；null 为跟随系统默认）。 */
    private var selectedVoiceName: String? = null

    /** TTS 引擎是否已就绪（未就绪时 [speak] 会排队，就绪后自动补播）。 */
    val isReady: Boolean get() = _isReady

    /** 当前正在朗读的消息 id（聊天页消息底部喇叭动画与顶部开关共享）；null 表示当前无朗读。 */
    private val _readingMessageId = MutableStateFlow<String?>(null)
    val readingMessageId: StateFlow<String?> = _readingMessageId.asStateFlow()

    /** 聊天页右上角「自动朗读」开关：开启后每次回复完成即自动朗读该回复。 */
    private val _autoReadEnabled = MutableStateFlow(false)
    val autoReadEnabled: StateFlow<Boolean> = _autoReadEnabled.asStateFlow()

    /** 引擎可用朗读语音列表（按展示名排序），供设置页「朗读语音」选择。 */
    private val _availableVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceInfo>> = _availableVoices.asStateFlow()

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                refreshVoices(engine)
                val result = engine.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setSpeechRate(1.1f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        AppDebugLogStore.d(TAG, "TTS done: $utteranceId")
                        if (utteranceId != null && utteranceId == _readingMessageId.value) {
                            _readingMessageId.value = null
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        AppDebugLogStore.e(TAG, "TTS error: $utteranceId")
                        if (utteranceId != null && utteranceId == _readingMessageId.value) {
                            _readingMessageId.value = null
                        }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        AppDebugLogStore.d(TAG, "TTS stopped: $utteranceId")
                        if (utteranceId != null && utteranceId == _readingMessageId.value) {
                            _readingMessageId.value = null
                        }
                    }
                })
                _isReady = true
                // 应用排队中的语音选择（优先于排队文本，避免先播再换声）。
                pendingVoiceName?.let {
                    pendingVoiceName = null
                    applyVoiceInternal(it)
                }
                // 播放待处理的文本
                pendingText?.let {
                    speakInternal(it, pendingMessageId)
                    pendingText = null
                    pendingMessageId = null
                }
            }
        } else {
            AppDebugLogStore.e(TAG, "TTS init failed: $status")
        }
    }

    /** 从引擎 voices 构建可用语音列表（locale 展示名 + voice.name）。 */
    private fun refreshVoices(engine: TextToSpeech) {
        val voices = engine.voices ?: return
        _availableVoices.value = voices
            .map { voice ->
                val localeName = runCatching {
                    val lang = voice.locale.getDisplayLanguage(Locale.CHINESE)
                    val country = voice.locale.country.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
                    "$lang$country"
                }.getOrElse { voice.locale.toLanguageTag() }
                TtsVoiceInfo(
                    name = voice.name,
                    displayName = "$localeName · ${voice.name}",
                    locale = voice.locale.toLanguageTag()
                )
            }
            .sortedBy { it.displayName }
    }

    /**
     * 切换朗读语音（对应 AppSettings.ttsVoiceName）。
     *
     * @param name 引擎 voice.name；null 表示跟随系统默认（回到默认中文语音）。
     */
    fun setVoice(name: String?) {
        selectedVoiceName = name
        if (_isReady) {
            applyVoiceInternal(name)
        } else {
            pendingVoiceName = name
            AppDebugLogStore.d(TAG, "TTS not ready, queuing voice $name")
        }
    }

    private fun applyVoiceInternal(name: String?) {
        tts?.let { engine ->
            if (name == null) {
                // 恢复默认：按中文语言选择系统默认语音。
                engine.setLanguage(Locale.CHINESE)
                AppDebugLogStore.d(TAG, "TTS voice reset to system default")
            } else {
                val voice = engine.voices?.firstOrNull { it.name == name }
                if (voice != null) {
                    engine.setVoice(voice)
                    AppDebugLogStore.d(TAG, "TTS voice set: $name")
                } else {
                    AppDebugLogStore.w(TAG, "TTS voice not found, fallback: $name")
                    engine.setLanguage(Locale.CHINESE)
                }
            }
        }
    }

    /** 朗读文本；[messageId] 用于标记「正在朗读哪条消息」（聊天页喇叭动画依据）。 */
    fun speak(text: String, messageId: String? = null) {
        if (_isReady) {
            speakInternal(text, messageId)
        } else {
            pendingText = text
            pendingMessageId = messageId
            AppDebugLogStore.d(TAG, "TTS not ready, queuing text")
        }
    }

    private fun speakInternal(text: String, messageId: String? = null) {
        tts?.let { engine ->
            engine.stop()
            _readingMessageId.value = messageId
            val utteranceId = messageId ?: "hanako_${System.currentTimeMillis()}"
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            AppDebugLogStore.d(TAG, "TTS speaking: ${text.take(50)}...")
        }
    }

    /** 停止当前朗读并复位朗读状态。 */
    fun stop() {
        tts?.stop()
        _readingMessageId.value = null
    }

    /** 切换右上角自动朗读开关；同时停止当前朗读（含手动朗读），供「再点一次关闭朗读」使用。 */
    fun toggleAutoRead() {
        _autoReadEnabled.value = !_autoReadEnabled.value
        stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isReady = false
        _readingMessageId.value = null
        _autoReadEnabled.value = false
    }
}
