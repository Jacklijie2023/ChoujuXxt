package `fun`.kirari.hanako.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlin.math.abs

/**
 * 灰度缩略图：每行只保留 [width] 个采样点，用于快速判断两张截图在竖直方向的重叠行数。
 *
 * 独立成纯数据类是为了让重叠算法可以在 JVM 单元测试里直接构造、无需 Android Bitmap。
 */
internal class GrayImage(val width: Int, val height: Int, val gray: IntArray)

/**
 * 两行采样点是否足够相似。
 *
 * 判定偏严格：平均灰度差不超过 [LongScreenshotCapturer.ROW_TOLERANCE]，
 * 且单点差异超过 [LongScreenshotCapturer.SAMPLE_TOLERANCE] 的采样点不超过 2 个。
 * 宁可判「无重叠」（多保留一点重复内容），也不要把真实的新内容当成重复裁掉。
 */
internal fun rowSimilar(
    a: GrayImage,
    ay: Int,
    b: GrayImage,
    by: Int,
    tolerance: Int = LongScreenshotCapturer.ROW_TOLERANCE
): Boolean {
    if (a.width != b.width || ay !in 0 until a.height || by !in 0 until b.height) return false
    var diff = 0
    var outliers = 0
    val baseA = ay * a.width
    val baseB = by * b.width
    for (x in 0 until a.width) {
        val delta = abs(a.gray[baseA + x] - b.gray[baseB + x])
        diff += delta
        if (delta > LongScreenshotCapturer.SAMPLE_TOLERANCE) outliers++
    }
    return diff <= tolerance * a.width && outliers <= 2
}

/**
 * 求 [base] 底部与 [next] 顶部的重叠行数（0 表示未找到可靠重叠）。
 *
 * 从最大可能重叠往下试：滚动越少，重叠越多；返回**最大**的可靠重叠，从而尽量删掉重复内容。
 */
internal fun findOverlapRows(
    base: GrayImage,
    next: GrayImage,
    minOverlap: Int = LongScreenshotCapturer.MIN_OVERLAP_ROWS
): Int {
    if (base.width != next.width) return 0
    val maxOverlap = minOf(base.height, next.height)
    if (maxOverlap < minOverlap) return 0
    for (h in maxOverlap downTo minOverlap) {
        if (rangeSimilar(base, base.height - h, next, 0, h)) return h
    }
    return 0
}

/**
 * 比较两段行区间是否相似：先按固定步长稀疏抽样，通过后再逐行确认，
 * 既减少逐行比较的次数，也不会因为抽样漏掉真实的重复内容。
 */
private fun rangeSimilar(
    a: GrayImage,
    aStart: Int,
    b: GrayImage,
    bStart: Int,
    count: Int
): Boolean {
    val step = if (count > 96) 4 else 1
    var offset = 0
    while (offset < count) {
        if (!rowSimilar(a, aStart + offset, b, bStart + offset)) return false
        offset += step
    }
    if (step > 1) {
        for (i in 0 until count) {
            if (!rowSimilar(a, aStart + i, b, bStart + i)) return false
        }
    }
    return true
}

/**
 * 长截图（用户手动逐屏拼接）。
 *
 * 不由程序自动滚动页面：用户自己滚到下一屏，每点一次悬浮球截一张，这里负责
 * 去掉新一屏与已有长图重复的顶部，再竖直接到下方。采集始终用
 * `occludingCaptureRequired = true`，避免把悬浮球/面板拼进长图。
 */
internal object LongScreenshotCapturer {
    private const val TAG = "LongShot"

    /**
     * 单张长图的高度上限（像素）。
     *
     * 1080×16000 的 ARGB 位图约 69MB，再高极易触发 OOM 并把整个进程带走
     * （进程一死，MediaProjection 会话丢失，之后所有截图都会报「会话未初始化」）。
     */
    internal const val MAX_STITCH_HEIGHT = 16_000

    /** 采样列数：越大越准、越慢；32 列在准确与耗电之间足够。 */
    internal const val SAMPLE_COLUMNS = 32

    /** 平均每采样点的灰度容差：只吸收轻微的抗锯齿差异，避免误判。 */
    internal const val ROW_TOLERANCE = 6

    /** 单个采样点的最大允许差异（少量边缘像素允许跳变）。 */
    internal const val SAMPLE_TOLERANCE = 32

    /** 少于该行数的重叠视为噪声，不做裁剪（避免误删正文）。 */
    internal const val MIN_OVERLAP_ROWS = 40

    /** 一次追加的结果（[addedRows] 为 0 表示画面没有变化）。 */
    internal class AppendResult(
        val bitmap: Bitmap,
        val overlapRows: Int,
        val addedRows: Int
    )

    /**
     * 把新一屏 [next] 拼到已有长图 [base] 下方，自动裁掉重复的顶部。
     *
     * 不改动 [base]；[next] 由调用方负责回收。
     */
    internal fun appendScreen(base: Bitmap, next: Bitmap): AppendResult {
        val overlap = if (next.width == base.width) {
            findOverlapRows(grayOf(base), grayOf(next))
        } else {
            0
        }
        val addedRows = (next.height - overlap).coerceAtLeast(0)
        val merged = appendBelow(base, next, overlap)
        AppDebugLogStore.i(
            TAG,
            "appendScreen overlap=$overlap addedRows=$addedRows size=${merged.width}x${merged.height}"
        )
        return AppendResult(merged, overlap, addedRows)
    }

    /** 把 [next] 去掉顶部 [overlapRows] 行后拼到 [base] 下方。 */
    internal fun appendBelow(base: Bitmap, next: Bitmap, overlapRows: Int): Bitmap {
        val newRows = next.height - overlapRows
        val result = Bitmap.createBitmap(base.width, base.height + newRows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(base, 0f, 0f, null)
        canvas.drawBitmap(
            next,
            Rect(0, overlapRows, next.width, next.height),
            Rect(0, base.height, base.width, base.height + newRows),
            null
        )
        return result
    }

    /** 生成降采样灰度图（只保留 [SAMPLE_COLUMNS] 列），供重叠检测使用。 */
    internal fun grayOf(bitmap: Bitmap): GrayImage {
        val width = minOf(SAMPLE_COLUMNS, bitmap.width)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, bitmap.height, false)
        val pixels = IntArray(width * bitmap.height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
        if (scaled != bitmap) scaled.recycle()
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            gray[i] = (((color shr 16) and 0xFF) * 299 +
                ((color shr 8) and 0xFF) * 587 +
                (color and 0xFF) * 114) / 1000
        }
        return GrayImage(width, bitmap.height, gray)
    }
}

internal fun Bitmap.recycleSafely() {
    if (!isRecycled) runCatching { recycle() }
}
