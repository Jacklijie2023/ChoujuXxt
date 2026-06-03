package `fun`.kirari.hanako.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 首次启动引导管理
 */
object FirstLaunchGuide {
    private const val PREFS_NAME = "hanako_launch_prefs"
    private const val KEY_FIRST_LAUNCH = "is_first_launch"
    private const val KEY_GUIDE_COMPLETED = "guide_completed"
    private const val KEY_ACCESSIBILITY_HINTED = "accessibility_hinted"
    private const val KEY_OVERLAY_HINTED = "overlay_hinted"
    private const val KEY_SHIZUKU_HINTED = "shizuku_hinted"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 是否是首次启动
     */
    fun isFirstLaunch(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FIRST_LAUNCH, true)
    }

    /**
     * 标记已启动过
     */
    fun markLaunched(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    /**
     * 引导是否已完成
     */
    fun isGuideCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GUIDE_COMPLETED, false)
    }

    /**
     * 标记引导完成
     */
    fun markGuideCompleted(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_GUIDE_COMPLETED, true).apply()
    }

    /**
     * 是否已提示过无障碍服务
     */
    fun isAccessibilityHinted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ACCESSIBILITY_HINTED, false)
    }

    fun markAccessibilityHinted(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_ACCESSIBILITY_HINTED, true).apply()
    }

    /**
     * 是否已提示过悬浮窗
     */
    fun isOverlayHinted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OVERLAY_HINTED, false)
    }

    fun markOverlayHinted(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_OVERLAY_HINTED, true).apply()
    }

    /**
     * 是否已提示过Shizuku
     */
    fun isShizukuHinted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHIZUKU_HINTED, false)
    }

    fun markShizukuHinted(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_SHIZUKU_HINTED, true).apply()
    }

    /**
     * 重置所有引导状态（用于测试）
     */
    fun resetAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
