package `fun`.kirari.hanako.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityReadResultTest {

    @Test
    fun textAndWindowMetadataShareSingleResult() {
        // 文本与窗口元数据来自同一次读取结果，而不是拆成两次调用拼装。
        val result = AccessibilityReadResult(
            text = "题目文字",
            packageName = "com.example.quiz",
            windowType = 1,
            layer = 0,
            isActive = true,
            isFocused = true,
            readAtMillis = 123L
        )
        assertEquals("题目文字", result.text)
        assertEquals("com.example.quiz", result.packageName)
        assertEquals(1, result.windowType)
        assertEquals(0, result.layer)
        assertTrue(result.isActive)
        assertTrue(result.isFocused)
        assertEquals(123L, result.readAtMillis)
    }

    @Test
    fun emptyMeansNoValidContent() {
        // 空结果（text=null 且无窗口元数据）：调用方必须据此判定读取失败，不得当作成功。
        val result = AccessibilityReadResult.empty()
        assertNull(result.text)
        assertNull(result.packageName)
        assertNull(result.windowType)
        assertFalse(result.isActive)
        assertFalse(result.isFocused)
    }
}