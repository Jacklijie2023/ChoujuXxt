package `fun`.kirari.hanako.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长截图重叠检测的纯 JVM 测试（不涉及 Android Bitmap / 截图后端）。
 *
 * 用「每行一个稳定灰度图案」的合成数据模拟连续多屏的页面内容，
 * 验证滚动后能算出正确的重复行数，从而只拼接新增部分。
 */
class LongScreenshotStitcherTest {

    private val width = 32

    /**
     * 第 [globalRow] 行（全局坐标）的采样值。
     *
     * 按全局行号做种子的伪随机灰度：任意两行几乎不可能相似，能真实检验
     * 「只有真正重复的行区间才被判为重叠」。早先用线性公式生成时，
     * 行号相差 7 的两行灰度只差 3（模 256 回绕），会被误判成重复内容。
     */
    private fun rowValues(globalRow: Int): IntArray {
        val random = java.util.Random(globalRow.toLong() * 7919L + 17L)
        return IntArray(width) { random.nextInt(256) }
    }

    private fun image(globalStartRow: Int, height: Int): GrayImage {
        val gray = IntArray(width * height)
        for (row in 0 until height) {
            val values = rowValues(globalStartRow + row)
            for (col in 0 until width) {
                gray[row * width + col] = values[col]
            }
        }
        return GrayImage(width, height, gray)
    }

    @Test
    fun overlapping_scroll_reports_repeated_rows() {
        // 上一屏是第 0..119 行，下一屏从第 60 行开始：重复 60 行。
        val base = image(globalStartRow = 0, height = 120)
        val next = image(globalStartRow = 60, height = 120)

        assertEquals("应识别出 60 行重复内容", 60, findOverlapRows(base, next))
    }

    @Test
    fun identical_screens_report_full_overlap() {
        val base = image(globalStartRow = 0, height = 120)
        val same = image(globalStartRow = 0, height = 120)

        assertEquals("同一屏必须被判定为完全重叠（用于判定已到底）", 120, findOverlapRows(base, same))
    }

    @Test
    fun unrelated_screens_report_no_overlap() {
        val base = image(globalStartRow = 0, height = 120)
        // 另一份内容：全局行号完全错开，不会与 base 的任意连续行区间匹配。
        val other = image(globalStartRow = 5000, height = 120)

        assertEquals("无重复内容时应返回 0（整屏拼接）", 0, findOverlapRows(base, other))
    }

    @Test
    fun small_overlap_below_threshold_is_ignored() {
        // 只重复 20 行，低于阈值 40：视为噪声，不做裁剪。
        val base = image(globalStartRow = 0, height = 120)
        val next = image(globalStartRow = 100, height = 120)

        assertEquals("小于阈值的重叠加算作无重叠", 0, findOverlapRows(base, next))
    }
}
