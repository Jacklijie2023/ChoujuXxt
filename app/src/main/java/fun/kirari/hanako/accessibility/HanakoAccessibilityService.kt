package `fun`.kirari.hanako.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍服务，用于从屏幕 UI 节点树中直接提取文本。
 * 支持多关键字过滤、节点类型过滤、实时监听模式。
 */
class HanakoAccessibilityService : AccessibilityService() {

    // 实时监听状态
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private var monitoringKeywords: List<String>? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 实时监听模式下，自动提取文本
        if (_isMonitoring.value) {
            val text = extractTextFromScreen(monitoringKeywords)
            if (text.isNotBlank()) {
                _liveText.value = text
            }
        }
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isMonitoring.value = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    companion object {
        private const val TAG = "HanakoA11y"
        private const val OWN_PACKAGE = "fun.kirari.hanako"
        private var instance: HanakoAccessibilityService? = null

        /** 服务是否正在运行 */
        fun isRunning(): Boolean = instance != null

        /** 启动实时监听 */
        fun startMonitoring(keywords: List<String>? = null) {
            instance?.let {
                it.monitoringKeywords = keywords
                it._isMonitoring.value = true
                Log.d(TAG, "Monitoring started with keywords: $keywords")
            }
        }

        /** 停止实时监听 */
        fun stopMonitoring() {
            instance?.let {
                it._isMonitoring.value = false
                it.monitoringKeywords = null
                Log.d(TAG, "Monitoring stopped")
            }
        }

        /**
         * 从当前屏幕提取全部文本。
         * @param keywords 可选关键字列表，为 null 时返回全部文本，非空时返回包含任意关键字的文本
         */
        fun extractTextFromScreen(keywords: List<String>? = null): String {
            val service = instance ?: return ""
            val root = service.rootInActiveWindow ?: return ""
            if (root.packageName?.toString() == OWN_PACKAGE) return ""

            val result = StringBuilder()
            traverseNodeText(root, keywords, result)
            return result.toString()
        }

        /**
         * 从当前屏幕提取文本（单关键字版本，兼容旧调用）。
         */
        fun extractTextFromScreen(keyword: String?): String {
            return extractTextFromScreen(keyword?.let { listOf(it) })
        }

        /**
         * 从指定屏幕区域提取文本。
         * @param zoneOnScreen 屏幕坐标系中的矩形区域
         * @param nodeTypes 要提取的节点类型（如 "android.widget.TextView", "android.widget.EditText"），为空时提取所有
         */
        fun extractTextFromZone(
            zoneOnScreen: Rect,
            nodeTypes: List<String> = emptyList()
        ): String {
            val service = instance ?: return ""
            val root = service.rootInActiveWindow ?: return ""
            if (root.packageName?.toString() == OWN_PACKAGE) return ""

            val result = StringBuilder()
            traverseNodeRect(root, zoneOnScreen, nodeTypes, result)
            return result.toString()
        }

        /**
         * 提取屏幕上的所有可交互元素信息（用于智能分析）。
         */
        fun extractInteractiveElements(): List<InteractiveElement> {
            val service = instance ?: return emptyList()
            val root = service.rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() == OWN_PACKAGE) return emptyList()

            val elements = mutableListOf<InteractiveElement>()
            traverseNodeInteractive(root, elements)
            return elements
        }

        private fun traverseNodeText(
            nodeInfo: AccessibilityNodeInfo,
            keywords: List<String>?,
            result: StringBuilder
        ) {
            val text = nodeInfo.text
            if (!text.isNullOrEmpty()) {
                if (keywords.isNullOrEmpty() || keywords.any { text.contains(it, ignoreCase = true) }) {
                    if (result.isNotEmpty()) result.append('\n')
                    result.append(text)
                }
            }

            for (i in 0 until nodeInfo.childCount) {
                val child = nodeInfo.getChild(i) ?: continue
                traverseNodeText(child, keywords, result)
            }
        }

        private fun traverseNodeRect(
            nodeInfo: AccessibilityNodeInfo,
            zone: Rect,
            nodeTypes: List<String>,
            result: StringBuilder
        ) {
            val nodeRect = Rect()
            nodeInfo.getBoundsInScreen(nodeRect)

            if (zone.contains(nodeRect)) {
                val className = nodeInfo.className?.toString() ?: ""
                if (nodeTypes.isEmpty() || nodeTypes.any { className.contains(it) }) {
                    val text = nodeInfo.text
                    if (!text.isNullOrEmpty()) {
                        if (result.isNotEmpty()) result.append('\n')
                        result.append(text)
                    }
                }
            }

            for (i in 0 until nodeInfo.childCount) {
                val child = nodeInfo.getChild(i) ?: continue
                traverseNodeRect(child, zone, nodeTypes, result)
            }
        }

        private fun traverseNodeInteractive(
            nodeInfo: AccessibilityNodeInfo,
            elements: MutableList<InteractiveElement>
        ) {
            val text = nodeInfo.text?.toString()
            val desc = nodeInfo.contentDescription?.toString()
            val className = nodeInfo.className?.toString() ?: ""
            val isClickable = nodeInfo.isClickable
            val isEditable = nodeInfo.isEditable
            val bounds = Rect()
            nodeInfo.getBoundsInScreen(bounds)

            if (!text.isNullOrBlank() || !desc.isNullOrBlank() || isEditable) {
                elements.add(
                    InteractiveElement(
                        text = text ?: "",
                        description = desc ?: "",
                        className = className,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        bounds = bounds
                    )
                )
            }

            for (i in 0 until nodeInfo.childCount) {
                val child = nodeInfo.getChild(i) ?: continue
                traverseNodeInteractive(child, elements)
            }
        }
    }
}

/**
 * 屏幕上的可交互元素
 */
data class InteractiveElement(
    val text: String,
    val description: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect
)
