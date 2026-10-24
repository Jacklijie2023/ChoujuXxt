package `fun`.kirari.hanako.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionExtractorTest {

    @Test
    fun `资料分析按材料题干选项还原`() {
        val text = """
            资料分析
            2018年，全国粮食产量为65789万吨，比上年增加616万吨。
            根据上述资料，下列说法正确的是：
            A. 2018年粮食产量比上年下降
            B. 夏粮产量超过粮食总产量的一半
            C. 粮食产量实现增长
            D. 以上都不对
        """.trimIndent()

        val question = QuestionExtractor.extract(text)

        assertEquals(listOf("2018年，全国粮食产量为65789万吨，比上年增加616万吨。"), question.material)
        assertEquals("根据上述资料，下列说法正确的是：", question.stem)
        assertEquals(listOf("A", "B", "C", "D"), question.options.map { it.label })
        assertEquals("2018年粮食产量比上年下降", question.options.first().text)
    }

    @Test
    fun `多段材料都保留在材料区`() {
        val text = """
            资料分析
            2017年，某市完成地区生产总值1200亿元。
            2018年，该市完成地区生产总值1350亿元。
            根据以上材料，回答下列问题：
            A. 增长了150亿元
            B. 下降了150亿元
            C. 没有变化
            D. 无法判断
        """.trimIndent()

        val question = QuestionExtractor.extract(text)

        assertEquals(2, question.material.size)
        assertEquals("根据以上材料，回答下列问题：", question.stem)
        assertEquals(4, question.options.size)
    }

    @Test
    fun `一行内多个选项可以被拆分`() {
        val text = "下列各句中，没有语病的一句是（ ）\nA. 甲  B. 乙  C. 丙  D. 丁"

        val question = QuestionExtractor.extract(text)

        assertEquals(listOf("甲", "乙", "丙", "丁"), question.options.map { it.text })
        assertEquals("下列各句中，没有语病的一句是（ ）", question.stem)
        assertTrue(question.material.isEmpty())
    }

    @Test
    fun `言语理解无材料时模块名与题号被过滤`() {
        val text = """
            言语理解与表达
            第 115 题
            下列词语中，没有错别字的一项是（ ）
            A. 一如既往
            B. 一愁莫展
            C. 迫不急待
            D. 按步就班
        """.trimIndent()

        val question = QuestionExtractor.extract(text)

        assertTrue(question.material.isEmpty())
        assertEquals("下列词语中，没有错别字的一项是（ ）", question.stem)
        assertEquals(4, question.options.size)
        assertEquals("一愁莫展", question.options[1].text)
    }

    @Test
    fun `没有选项时整段作为题干`() {
        val question = QuestionExtractor.extract("根据材料，概括作者的主要观点。")

        assertTrue(question.options.isEmpty())
        assertEquals("根据材料，概括作者的主要观点。", question.stem)
        assertTrue(question.hasContent)
    }

    @Test
    fun `空文本不产生内容`() {
        val question = QuestionExtractor.extract("")

        assertFalse(question.hasContent)
        assertTrue(question.material.isEmpty())
        assertEquals("", question.stem)
    }

    @Test
    fun `长截图重叠识别出的重复行只保留一次`() {
        val text = """
            2018年，全国粮食产量为65789万吨，比上年增加616万吨。
            2018年，全国粮食产量为65789万吨，比上年增加616万吨。
            根据上述资料，下列说法正确的是：
            A. 粮食产量下降
            B. 粮食产量增长
            C. 无法判断
            D. 以上都对
        """.trimIndent()

        val question = QuestionExtractor.extract(text)

        assertEquals(1, question.material.size)
        assertEquals("根据上述资料，下列说法正确的是：", question.stem)
    }

    @Test
    fun `空格差异的重复行也会被过滤`() {
        val text = """
            2018年，全国粮食产量为65789万吨，比上年增加616万吨。
            2018 年，全国粮食产量为 65789 万吨，比上年增加 616 万吨。
            A. 粮食产量下降
            B. 粮食产量增长
            C. 无法判断
            D. 以上都对
        """.trimIndent()

        val question = QuestionExtractor.extract(text)

        assertEquals(1, question.material.size)
    }

    @Test
    fun `被截断的同一选项保留更完整的一份`() {
        val text = """
            根据上述资料，下列说法正确的是：
            A. 2018年粮食产
            A. 2018年粮食产量比上年下降
            B. 夏粮产量超过一半
            C. 粮食产量实现增长
            D. 以上都不对
        """.trimIndent()

        val question = QuestionExtractor.extract(text)

        assertEquals(4, question.options.size)
        assertEquals("2018年粮食产量比上年下降", question.options.first().text)
    }
}
