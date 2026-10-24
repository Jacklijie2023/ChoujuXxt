package `fun`.kirari.hanako.ui

import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ProcessingRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModeSelectionTest {

    @Test
    fun selectRealTimeEnablesRealTimeAndKeepsRoute() {
        val base = AppSettings(processingRoute = ProcessingRoute.ACCESSIBILITY_TEXT)
        val result = homeModeTransform(null)(base)
        assertTrue(result.realTimeMode)
        // 开启实时互动时保留原处理路线，不被覆盖。
        assertEquals(ProcessingRoute.ACCESSIBILITY_TEXT, result.processingRoute)
    }

    @Test
    fun selectRouteDisablesRealTimeAndSetsRoute() {
        val base = AppSettings(processingRoute = ProcessingRoute.OCR_THEN_LLM, realTimeMode = true)
        val result = homeModeTransform(ProcessingRoute.MULTIMODAL_DIRECT)(base)
        assertFalse(result.realTimeMode)
        assertEquals(ProcessingRoute.MULTIMODAL_DIRECT, result.processingRoute)
    }

    @Test
    fun selectAccessibilityDisablesRealTime() {
        val base = AppSettings(processingRoute = ProcessingRoute.OCR_THEN_LLM, realTimeMode = true)
        val result = homeModeTransform(ProcessingRoute.ACCESSIBILITY_TEXT)(base)
        assertFalse(result.realTimeMode)
        assertEquals(ProcessingRoute.ACCESSIBILITY_TEXT, result.processingRoute)
    }

    @Test
    fun selectOcrDisablesRealTime() {
        val base = AppSettings(processingRoute = ProcessingRoute.ACCESSIBILITY_TEXT, realTimeMode = true)
        val result = homeModeTransform(ProcessingRoute.OCR_THEN_LLM)(base)
        assertFalse(result.realTimeMode)
        assertEquals(ProcessingRoute.OCR_THEN_LLM, result.processingRoute)
    }
}