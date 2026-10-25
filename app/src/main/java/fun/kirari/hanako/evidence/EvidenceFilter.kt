package `fun`.kirari.hanako.evidence

/**
 * 可回溯的本地规则过滤器（**纯函数**，无 Android / 网络 / 模型依赖，可直接 JVM 单测）。
 *
 * 设计原则（需求 §4）：
 * 1. **不销毁原文**：本类只产出 [ClassifiedBlock] 派生记录，原始 [EvidenceRecord] 一字不改；
 * 2. **确定性优先**：来源包名 / 窗口类型 / 节点类型 / 坐标 / 置信度 → 规则 → 语义类别；
 * 3. **不误删有效内容**：只有「确定是界面噪声」才 EXCLUDE；把握不足一律 UNCERTAIN（仍保留、可回查）；
 * 4. **可重跑**：输出带 [RULE_VERSION]，规则变化后可对同一批原始证据重新执行并对照。
 */
internal object EvidenceFilter {

    /** 规则版本：任何规则调整都必须提升此版本号，测试中心据此对照不同版本结果。 */
    const val RULE_VERSION = "evidence-filter-v1"

    /** 系统界面 / 输入法 / 桌面：这些来源的文本不可能是题目正文。 */
    private val SYSTEM_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.baidu.input",
        "com.tencent.qqpinyin",
        "com.miui.home",
        "com.android.launcher3"
    )

    private val KEYBOARD_CLASS_HINTS = listOf(
        "keyboard", "inputmethod", "keyview", "softinput"
    )

    /** 明确的界面按钮 / 导航文案（整行精确匹配，避免误删题干中的同名词）。 */
    private val UI_LABEL_EXACT = setOf(
        "分享", "收藏", "举报", "纠错", "笔记", "答题卡", "交卷", "提交", "确定", "取消",
        "返回", "关闭", "上一题", "下一题", "跳过", "查看解析", "查看答案", "答案解析",
        "全部", "只看错题", "收藏本题", "加入错题本", "复制", "粘贴", "搜索", "更多",
        "设置", "帮助", "反馈", "登录", "注册", "立即购买", "开通会员", "继续", "完成"
    )

    private val OPTION_MARKER = Regex("^([A-Ha-h][.、．:：)）]|[（(][A-Ha-h][）)]|[①②③④⑤⑥⑦⑧])")
    private val QUESTION_NUMBER = Regex("^\\d{1,4}\\s*[.、．)）]")
    private val QUESTION_NUMBER_CN = Regex("^第\\s*\\d{1,4}\\s*题")
    private val MATERIAL_HINT = Regex("(材料|资料|阅读以下|根据以下|根据上述|结合材料)")
    private val FIGURE_HINT = Regex("(如图|图\\s*\\d|图例|柱状图|折线图|饼图|坐标轴|统计图|表格)")
    private val TABLE_HINT = Regex("(\\||\\t|\\s{3,}\\S+\\s{3,})")
    private val FORMULA_HINT = Regex("[=≈≠≤≥√∫∑±×÷∞πθαβγΔ°%‰]|\\^|\\^\\d|\\d+\\s*/\\s*\\d+")

    /** 无坐标时的系统栏估算高度（只有拿到屏幕高度时才启用）。 */
    private const val STATUS_BAR_ESTIMATE_PX = 60
    private const val NAV_BAR_ESTIMATE_PX = 120

    /**
     * 对一个原始证据的全部文本单元执行分类。
     *
     * 返回值与输入一一对应（同 [FilterInputBlock.lineIndex]），调用方不得丢弃 UNCERTAIN。
     */
    fun classify(
        evidence: EvidenceRecord,
        context: FilterContext,
        processedAtMillis: Long
    ): List<ClassifiedBlock> = evidence.filterBlocks().map { block ->
        classifyOne(evidence, block, context, processedAtMillis)
    }

    private fun classifyOne(
        evidence: EvidenceRecord,
        block: FilterInputBlock,
        context: FilterContext,
        processedAtMillis: Long
    ): ClassifiedBlock {
        val text = block.text.trim()
        fun result(
            verdict: BlockVerdict,
            category: BlockCategory,
            reason: String,
            confidence: Float
        ) = ClassifiedBlock(
            evidenceId = evidence.evidenceId,
            lineIndex = block.lineIndex,
            text = block.text,
            verdict = verdict,
            category = category,
            reason = reason,
            ruleVersion = RULE_VERSION,
            confidence = confidence,
            processedAtMillis = processedAtMillis
        )

        if (text.isBlank()) {
            return result(BlockVerdict.EXCLUDE, BlockCategory.UNKNOWN, "空白行", 1f)
        }

        // 1) 来源身份：丑橘自身 / 系统 / 输入法 —— 确定性噪声。
        val pkg = evidence.sourceApp?.trim()?.lowercase()
        if (pkg != null && pkg == context.ownPackage.lowercase()) {
            return result(BlockVerdict.EXCLUDE, BlockCategory.SELF_OVERLAY, "来源为丑橘自身浮层", 1f)
        }
        if (pkg != null && pkg in SYSTEM_PACKAGES) {
            return result(BlockVerdict.EXCLUDE, BlockCategory.SYSTEM_UI, "来源为系统界面/桌面", 1f)
        }
        val node = block.node
        val classHint = node?.className?.lowercase().orEmpty()
        if (KEYBOARD_CLASS_HINTS.any { it in classHint }) {
            return result(BlockVerdict.EXCLUDE, BlockCategory.KEYBOARD, "节点类型为输入法键盘", 1f)
        }
        if (node?.editable == true) {
            return result(BlockVerdict.EXCLUDE, BlockCategory.KEYBOARD, "可编辑输入框内容", 0.95f)
        }
        if (node != null && node.clickable && text in UI_LABEL_EXACT) {
            return result(BlockVerdict.EXCLUDE, BlockCategory.UI_NOISE, "可点击的固定界面按钮", 0.9f)
        }
        if (text in UI_LABEL_EXACT) {
            return result(BlockVerdict.EXCLUDE, BlockCategory.UI_NOISE, "固定界面按钮文案", 0.85f)
        }

        // 2) 坐标：落在系统状态栏 / 导航栏区域。
        val bounds = block.bounds
        val height = context.screenHeight
        if (bounds != null && height != null && height > 0) {
            if (bounds.bottom <= STATUS_BAR_ESTIMATE_PX) {
                return result(BlockVerdict.EXCLUDE, BlockCategory.SYSTEM_UI, "位于状态栏区域", 0.9f)
            }
            if (bounds.top >= height - NAV_BAR_ESTIMATE_PX) {
                return result(BlockVerdict.EXCLUDE, BlockCategory.SYSTEM_UI, "位于导航栏区域", 0.9f)
            }
        }

        // 3) 识别质量：低置信度不得当作已确认题目内容。
        val confidence = block.confidence ?: evidence.confidence
        if (confidence != null && confidence < 0.3f) {
            return result(BlockVerdict.UNCERTAIN, BlockCategory.UNKNOWN, "识别置信度过低（${confidence}）", confidence)
        }

        // 4) 选项：标签 + 正文必须保留（数字、单位、比例、短句均在正文内）。
        if (OPTION_MARKER.containsMatchIn(text)) {
            return result(BlockVerdict.KEEP, BlockCategory.OPTION, "行首为选项标签", 0.9f)
        }
        // 5) 题干 / 材料。
        if (MATERIAL_HINT.containsMatchIn(text)) {
            return result(BlockVerdict.KEEP, BlockCategory.MATERIAL, "命中材料提示词", 0.8f)
        }
        if (QUESTION_NUMBER.containsMatchIn(text) || QUESTION_NUMBER_CN.containsMatchIn(text)) {
            return result(BlockVerdict.KEEP, BlockCategory.QUESTION_STEM, "行首为题号", 0.8f)
        }
        // 6) 公式 / 表格 / 图表说明：优先按结构信号保留。
        if (FORMULA_HINT.containsMatchIn(text)) {
            return result(BlockVerdict.KEEP, BlockCategory.FORMULA, "命中公式字符或比例结构", 0.75f)
        }
        if (TABLE_HINT.containsMatchIn(text)) {
            return result(BlockVerdict.KEEP, BlockCategory.TABLE, "命中表格结构信号", 0.7f)
        }
        if (FIGURE_HINT.containsMatchIn(text)) {
            return result(BlockVerdict.KEEP, BlockCategory.FIGURE, "命中图表/图例提示词", 0.7f)
        }

        // 7) 极短且非数字：无法可靠判断，保留为待确认。
        val nonWhitespace = text.count { !it.isWhitespace() }
        if (nonWhitespace <= 1 && !text.first().isDigit()) {
            return result(BlockVerdict.UNCERTAIN, BlockCategory.UNKNOWN, "单字符行无法判定用途", 0.4f)
        }

        // 8) 来源身份未确认（无来源 / 非已绑定的目标应用）：不得当作已确认题目正文。
        val bound = context.boundTargetPackage
        if (bound.isNullOrBlank() || pkg == null || pkg != bound.lowercase()) {
            return result(BlockVerdict.UNCERTAIN, BlockCategory.UNKNOWN, "来源应用身份未确认", 0.5f)
        }

        // 9) 默认：来自已确认目标应用内容窗口的普通文本 → 保留为题目辅助内容。
        return result(
            BlockVerdict.KEEP,
            BlockCategory.QUESTION_AUXILIARY,
            "来源为目标应用内容窗口且未命中噪声规则",
            0.6f
        )
    }
}
