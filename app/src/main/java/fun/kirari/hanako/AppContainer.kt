package `fun`.kirari.hanako

import android.content.Context
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.NetworkClientProvider
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.overlay.TtsHelper
import `fun`.kirari.hanako.util.HapticHelper

internal class AppContainer(appContext: Context) {
    val networkClientProvider = NetworkClientProvider()
    val settingsStore = SettingsStore(appContext)
    val unifiedLLMClient = UnifiedLLMClient(clientProvider = networkClientProvider)
    val localOcrManager = LocalOcrManager(appContext)
    val settingsRepository = SettingsRepository(settingsStore)

    init {
        // 初始化 TTS
        TtsHelper.init(appContext)
        // 初始化触觉反馈
        HapticHelper.init(appContext)
    }
}
