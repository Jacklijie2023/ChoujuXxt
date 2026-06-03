package `fun`.kirari.hanako.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import `fun`.kirari.hanako.debug.AppDebugLogStore

/**
 * 无障碍操作辅助类
 * 提供自动填写、自动点击等功能
 */
object AccessibilityActions {
    private const val TAG = "A11yActions"

    /**
     * 自动填写输入框
     * @param hint 输入框的提示文字或标签，用于定位目标输入框
     * @param text 要填入的文本
     * @return 是否成功填写
     */
    fun autoFillInput(hint: String, text: String): Boolean {
        val service = HanakoAccessibilityService
        if (!service.isRunning()) {
            AppDebugLogStore.e(TAG, "Accessibility service not running")
            return false
        }

        // 查找匹配的输入框
        val rootNode = getRootNode() ?: return false
        val targetNode = findEditableNode(rootNode, hint)
        if (targetNode == null) {
            AppDebugLogStore.w(TAG, "No editable node found with hint: $hint")
            return false
        }

        // 填写文本
        val result = setTextToNode(targetNode, text)
        AppDebugLogStore.i(TAG, "autoFillInput result=$result hint=$hint text=${text.take(20)}")
        return result
    }

    /**
     * 自动点击按钮
     * @param buttonText 按钮上显示的文字
     * @return 是否成功点击
     */
    fun autoClickButton(buttonText: String): Boolean {
        val service = HanakoAccessibilityService
        if (!service.isRunning()) {
            AppDebugLogStore.e(TAG, "Accessibility service not running")
            return false
        }

        val rootNode = getRootNode() ?: return false
        val targetNode = findClickableNode(rootNode, buttonText)
        if (targetNode == null) {
            AppDebugLogStore.w(TAG, "No clickable node found with text: $buttonText")
            return false
        }

        val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppDebugLogStore.i(TAG, "autoClickButton result=$result buttonText=$buttonText")
        return result
    }

    private fun getRootNode(): AccessibilityNodeInfo? {
        // 通过反射获取 AccessibilityService 实例
        return try {
            val field = HanakoAccessibilityService::class.java.getDeclaredField("instance")
            field.isAccessible = true
            val service = field.get(null) as? HanakoAccessibilityService
            service?.rootInActiveWindow
        } catch (e: Exception) {
            AppDebugLogStore.e(TAG, "Failed to get root node", e)
            null
        }
    }

    private fun findEditableNode(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? {
        if (root.isEditable) {
            val text = root.text?.toString() ?: ""
            val desc = root.contentDescription?.toString() ?: ""
            val placeholder = root.hintText?.toString() ?: ""
            if (text.contains(hint, ignoreCase = true) ||
                desc.contains(hint, ignoreCase = true) ||
                placeholder.contains(hint, ignoreCase = true)
            ) {
                return root
            }
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findEditableNode(child, hint)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.isClickable) {
            val nodeText = root.text?.toString() ?: ""
            val desc = root.contentDescription?.toString() ?: ""
            if (nodeText.contains(text, ignoreCase = true) ||
                desc.contains(text, ignoreCase = true)
            ) {
                return root
            }
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findClickableNode(child, text)
            if (result != null) return result
        }
        return null
    }

    private fun setTextToNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // 先聚焦
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // 清空现有内容
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
        })

        // 填入新内容
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }
}
