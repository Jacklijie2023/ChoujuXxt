package `fun`.kirari.hanako.overlay

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import `fun`.kirari.hanako.debug.AppDebugLogStore
import java.util.Locale

/**
 * TTS 语音合成辅助类
 */
internal object TtsHelper : TextToSpeech.OnInitListener {
    private const val TAG = "HanakoTTS"
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setSpeechRate(1.1f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        AppDebugLogStore.d(TAG, "TTS done: $utteranceId")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        AppDebugLogStore.e(TAG, "TTS error: $utteranceId")
                    }
                })
                isReady = true
                // 播放待处理的文本
                pendingText?.let {
                    speakInternal(it)
                    pendingText = null
                }
            }
        } else {
            AppDebugLogStore.e(TAG, "TTS init failed: $status")
        }
    }

    fun speak(text: String) {
        if (isReady) {
            speakInternal(text)
        } else {
            pendingText = text
            AppDebugLogStore.d(TAG, "TTS not ready, queuing text")
        }
    }

    private fun speakInternal(text: String) {
        tts?.let { engine ->
            engine.stop()
            val utteranceId = "hanako_${System.currentTimeMillis()}"
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            AppDebugLogStore.d(TAG, "TTS speaking: ${text.take(50)}...")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
