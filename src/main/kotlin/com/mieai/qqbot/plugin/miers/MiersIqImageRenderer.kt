package com.mieai.qqbot.plugin.miers

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

data class MiersIqModel(
    val name: String,
    val iq: Double,
    val family: MiersModelFamily,
    val strength: String,
    val passedTasks: Int = 0,
    val totalTasks: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(iq in 0.0..MiersIqImageRenderer.IQ_SCALE_MAX) { "iq must be within the image scale" }
        require(strength in STRENGTHS) { "strength is unsupported" }
        require(totalTasks >= 0) { "totalTasks must not be negative" }
        require(passedTasks in 0..totalTasks) { "passedTasks must be within totalTasks" }
    }

    companion object {
        val STRENGTHS: Set<String> = setOf("ultra", "max", "xhigh", "high", "medium", "low")
    }
}

enum class MiersModelFamily(val rgb: Int) {
    SOL(0x58A6FF),
    ASTRA(0xF4D35E),
    TERRA(0xF2A65A),
    LUNA(0x6BCB8B),
    GPT55(0xE68AC3),
    DEEPSEEK(0xA98AF7),
    ;

    fun color(): Color = Color(rgb)
}

/*
 * Generates the image in-process. The deployed MieBot container cannot rely on
 * PowerShell, so this uses only Java 21 headless BufferedImage APIs.
 */
class MiersIqImageRenderer(
    private val models: List<MiersIqModel>,
) {
    private val modelGroups = models.groupBy(MiersIqModel::name)

    init {
        require(models.isNotEmpty()) { "the live IQ table must not be empty" }
    }

    fun renderPng(): ByteArray {
        val image = BufferedImage(WIDTH, imageHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            prepare(graphics)
            graphics.color = BACKGROUND
            graphics.fillRect(0, 0, WIDTH, imageHeight)
            drawHeader(graphics)
            drawGrid(graphics)
            drawRanking(graphics)
            drawFooter(graphics)
        } finally {
            graphics.dispose()
        }

        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG writer is unavailable" }
            output.toByteArray()
        }
    }

    private val rankingStartY: Int
        get() = GRID_Y + (modelGroups.size + GRID_COLUMNS - 1) / GRID_COLUMNS * (CARD_HEIGHT + CARD_GAP_Y) + 84

    private val footerY: Int
        get() = max(FOOTER_Y, rankingStartY + ((models.size + 1) / 2) * RANK_ROW_HEIGHT + 16)

    private val imageHeight: Int
        get() = max(HEIGHT, footerY + FOOTER_GAP)

    private fun drawHeader(graphics: Graphics2D) {
        drawText(graphics, "CODEXRADAR / INTELLIGENCE EFFICIENCY", eyebrowFont, MUTED, GRID_MARGIN_X, 36)
        drawText(graphics, "模型IQ", titleFont, TEXT, GRID_MARGIN_X, 76)
        drawText(graphics, "获取时间 ${LocalDateTime.now().format(FETCHED_AT_FORMATTER)}", metaFont, MUTED, WIDTH - GRID_MARGIN_X, 68, alignRight = true)
    }

    private fun drawGrid(graphics: Graphics2D) {
        val grouped = modelGroups
            .toSortedMap(compareBy<String> { MODEL_ORDER.indexOf(it).takeUnless { index -> index < 0 } ?: Int.MAX_VALUE }.thenBy { it })
        grouped.entries.forEachIndexed { index, (name, entries) ->
            val column = index % GRID_COLUMNS
            val row = index / GRID_COLUMNS
            val x = GRID_MARGIN_X + (column * (CARD_WIDTH + CARD_GAP_X))
            val y = GRID_Y + (row * (CARD_HEIGHT + CARD_GAP_Y))
            val accent = entries.first().family.color()

            graphics.color = SURFACE
            graphics.fillRoundRect(x, y, CARD_WIDTH, CARD_HEIGHT, CARD_RADIUS, CARD_RADIUS)
            graphics.color = BORDER
            graphics.stroke = BasicStroke(1f)
            graphics.drawRoundRect(x, y, CARD_WIDTH, CARD_HEIGHT, CARD_RADIUS, CARD_RADIUS)
            graphics.color = accent
            graphics.fillRect(x, y + 16, 4, 44)

            drawText(graphics, name, nameFont, TEXT, x + CARD_PADDING, y + 27)
            drawModelBadge(graphics, name, accent, x + CARD_WIDTH - CARD_PADDING - CARD_BADGE_SIZE, y + 10)
            val average = entries.map(MiersIqModel::iq).average()
            drawText(graphics, "AVG", cardMetaFont, MUTED, x + CARD_PADDING, y + 54)
            drawText(graphics, formatIq(average), averageFont, accent, x + CARD_PADDING + 42, y + 56)
            val passed = entries.sumOf(MiersIqModel::passedTasks)
            val total = entries.sumOf(MiersIqModel::totalTasks)
            if (total > 0) {
                drawText(graphics, "$passed/$total PASSED", cardMetaFont, MUTED, x + CARD_WIDTH - CARD_PADDING, y + 54, alignRight = true)
            }
            val strengths = entries.sortedBy { STRENGTH_ORDER.indexOf(it.strength) }
            strengths.forEachIndexed { cellIndex, model ->
                val cellColumn = cellIndex % CARD_CELL_COLUMNS
                val row = cellIndex / CARD_CELL_COLUMNS
                val cellX = x + CARD_PADDING + (cellColumn * (CARD_CELL_WIDTH + CARD_CELL_GAP_X))
                val cellY = y + CARD_CELL_TOP + (row * (CARD_CELL_HEIGHT + CARD_CELL_GAP_Y))
                graphics.color = TRACK
                graphics.fillRoundRect(cellX, cellY, CARD_CELL_WIDTH, CARD_CELL_HEIGHT, CARD_CELL_RADIUS, CARD_CELL_RADIUS)
                graphics.color = accent
                graphics.stroke = BasicStroke(1f)
                graphics.drawRoundRect(cellX, cellY, CARD_CELL_WIDTH, CARD_CELL_HEIGHT, CARD_CELL_RADIUS, CARD_CELL_RADIUS)
                drawText(graphics, model.strength.uppercase(Locale.ROOT), cellLabelFont, accent, cellX + 8, cellY + 17)
                drawText(graphics, formatIq(model.iq), cellValueFont, accent, cellX + 8, cellY + 41)
                if (model.totalTasks > 0) {
                    drawText(graphics, "${model.passedTasks}/${model.totalTasks}", cellMetaFont, MUTED, cellX + CARD_CELL_WIDTH - 7, cellY + 59, alignRight = true)
                }
            }
        }
    }

    private fun drawRanking(graphics: Graphics2D) {
        val ranked = models.withIndex()
            .sortedWith(compareByDescending<IndexedValue<MiersIqModel>> { it.value.iq }.thenBy { it.index })
            .map(IndexedValue<MiersIqModel>::value)

        val startY = rankingStartY
        drawText(graphics, "模型排行", titleFont, TEXT, 30, startY - 38)
        drawText(graphics, "按 IQ 从高到低 · 同分保持原始顺序", metaFont, MUTED, 245, startY - 41)
        val split = (ranked.size + 1) / 2
        listOf(ranked.take(split), ranked.drop(split)).forEachIndexed { column, columnModels ->
            val originX = if (column == 0) RANK_LEFT_X else RANK_RIGHT_X
            val nameX = originX + RANK_NAME_OFFSET
            val barX = originX + RANK_BAR_OFFSET
            val barWidth = RANK_BAR_WIDTH
            val valueX = originX + RANK_VALUE_OFFSET
            val axisBottomY = startY + (columnModels.lastIndex * RANK_ROW_HEIGHT) + 27
            TICKS.forEach { tick ->
                val x = barX + ((barWidth * tick) / IQ_SCALE_MAX).roundToInt()
                graphics.color = AXIS
                graphics.stroke = BasicStroke(1f)
                graphics.drawLine(x, startY - 4, x, axisBottomY)
                drawText(graphics, tick.toString(), rankTickFont, MUTED, x, startY - 17, centered = true)
            }
            columnModels.forEachIndexed { index, model ->
                val y = startY + (index * RANK_ROW_HEIGHT)
                val rank = index + if (column == 0) 1 else split + 1
                val accent = model.family.color()
                drawOutlinedPill(graphics, rank.toString(), unitFont, accent, originX, y + 3, 32, 24)
                val labelWidth = barX - nameX - 14
                val combinedLabel = model.name + " " + model.strength
                val label = if (graphics.getFontMetrics(nameFont).stringWidth(combinedLabel) <= labelWidth) {
                    combinedLabel
                } else {
                    model.name
                }
                val labelFont = fitFont(graphics, label, labelWidth)
                drawText(graphics, label, labelFont, TEXT, nameX, y + 19)
                graphics.color = TRACK
                graphics.fillRect(barX, y + 14, barWidth, 7)
                graphics.color = accent
                graphics.fillRect(barX, y + 14, fillWidth(barWidth, model.iq), 7)
                drawText(graphics, formatIq(model.iq), rankScoreFont, TEXT, valueX, y + 26)
            }
        }
    }

    private fun drawFooter(graphics: Graphics2D) {
        graphics.color = BORDER
        graphics.stroke = BasicStroke(1f)
        graphics.drawLine(GRID_MARGIN_X, footerY, WIDTH - GRID_MARGIN_X, footerY)
        drawText(graphics, "IQ 参考尺度 0-120", metaFont, MUTED, GRID_MARGIN_X, footerY + 33)
        drawText(graphics, "来源 codexradar.com | 实时抓取", metaFont, MUTED, WIDTH - GRID_MARGIN_X, footerY + 33, alignRight = true)
    }

    private fun prepare(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun drawOutlinedPill(
        graphics: Graphics2D,
        text: String,
        font: Font,
        color: Color,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val shape = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), height.toFloat() / 2f, height.toFloat() / 2f)
        graphics.color = TRACK
        graphics.fill(shape)
        graphics.color = color
        graphics.stroke = BasicStroke(1f)
        graphics.draw(shape)
        val metrics = fontMetrics(graphics, font)
        drawText(graphics, text, font, color, x + (width / 2), y + ((height + metrics.ascent - metrics.descent) / 2), centered = true)
    }

    private fun drawModelBadge(graphics: Graphics2D, name: String, accent: Color, x: Int, y: Int) {
        graphics.color = TRACK
        graphics.fillRoundRect(x, y, CARD_BADGE_SIZE, CARD_BADGE_SIZE, CARD_BADGE_RADIUS, CARD_BADGE_RADIUS)
        graphics.color = accent
        graphics.stroke = BasicStroke(1f)
        graphics.drawRoundRect(x, y, CARD_BADGE_SIZE, CARD_BADGE_SIZE, CARD_BADGE_RADIUS, CARD_BADGE_RADIUS)
        drawText(graphics, badgeText(name), badgeFont, TEXT, x + (CARD_BADGE_SIZE / 2), y + 19, centered = true)
    }

    private fun badgeText(name: String): String = when {
        name.startsWith("DeepSeek") -> "DS"
        name == "GPT5.5" -> "5.5"
        else -> name.substringAfterLast(' ').take(1)
    }

    private fun drawText(
        graphics: Graphics2D,
        text: String,
        font: Font,
        color: Color,
        x: Int,
        baselineY: Int,
        alignRight: Boolean = false,
        centered: Boolean = false,
    ) {
        graphics.font = font
        graphics.color = color
        val width = graphics.fontMetrics.stringWidth(text)
        val drawX = when {
            alignRight -> x - width
            centered -> x - (width / 2)
            else -> x
        }
        graphics.drawString(text, drawX, baselineY)
    }

    private fun ellipsize(metrics: FontMetrics, text: String, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end) + suffix
    }

    private fun fitFont(graphics: Graphics2D, text: String, maxWidth: Int): Font {
        var font = nameFont
        while (font.size2D > 8f && graphics.getFontMetrics(font).stringWidth(text) > maxWidth) {
            font = font.deriveFont(font.size2D - 0.5f)
        }
        return font
    }

    private fun fontMetrics(graphics: Graphics2D, font: Font): FontMetrics {
        graphics.font = font
        return graphics.fontMetrics
    }

    private fun fillWidth(width: Int, iq: Double): Int =
        max(3, (width * (iq / IQ_SCALE_MAX)).roundToInt())

    private fun formatIq(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    companion object {
        const val WIDTH: Int = 1210
        const val HEIGHT: Int = 1750
        const val MODEL_COUNT: Int = 29
        const val IQ_SCALE_MAX: Double = 120.0

        private const val GRID_COLUMNS = 5
        private const val GRID_MARGIN_X = 24
        private const val GRID_Y = 110
        private const val CARD_WIDTH = 220
        private const val CARD_HEIGHT = 230
        private const val CARD_RADIUS = 7
        private const val CARD_GAP_X = 6
        private const val CARD_GAP_Y = 12
        private const val CARD_PADDING = 14
        private const val CARD_CELL_TOP = 68
        private const val CARD_CELL_COLUMNS = 3
        private const val CARD_CELL_WIDTH = 58
        private const val CARD_CELL_HEIGHT = 65
        private const val CARD_CELL_GAP_X = 8
        private const val CARD_CELL_GAP_Y = 8
        private const val CARD_CELL_RADIUS = 6
        private const val CARD_BADGE_SIZE = 30
        private const val CARD_BADGE_RADIUS = 7
        private const val RANK_ROW_HEIGHT = 29
        private const val RANK_LEFT_X = 35
        private const val RANK_RIGHT_X = 625
        private const val RANK_NAME_OFFSET = 45
        private const val RANK_BAR_OFFSET = 175
        private const val RANK_BAR_WIDTH = 320
        private const val RANK_VALUE_OFFSET = 507
        private const val FOOTER_Y = 1640
        private const val FOOTER_GAP = 72

        private val TICKS = listOf(0, 20, 40, 60, 80, 100, 120)
        private val FETCHED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        private val BACKGROUND = color("#0D1117")
        private val SURFACE = color("#161B22")
        private val BORDER = color("#30363D")
        private val TEXT = color("#F0F6FC")
        private val MUTED = color("#8B949E")
        private val TRACK = color("#282E36")
        private val AXIS = Color(0x6E, 0x76, 0x81, 150)
        private val titleFont = Font("Microsoft YaHei UI", Font.BOLD, 28)
        private val eyebrowFont = Font("Segoe UI", Font.PLAIN, 12)
        private val metaFont = Font("Microsoft YaHei UI", Font.PLAIN, 14)
        private val nameFont = Font("Segoe UI", Font.PLAIN, 12)
        private val cardMetaFont = Font("Segoe UI", Font.PLAIN, 9)
        private val averageFont = Font("Segoe UI", Font.BOLD, 20)
        private val cellLabelFont = Font("Segoe UI", Font.BOLD, 8)
        private val cellValueFont = Font("Segoe UI", Font.PLAIN, 16)
        private val cellMetaFont = Font("Segoe UI", Font.PLAIN, 7)
        private val badgeFont = Font("Segoe UI", Font.BOLD, 9)
        private val rankTickFont = Font("Segoe UI", Font.PLAIN, 14)
        private val unitFont = Font("Segoe UI", Font.PLAIN, 13)
        private val rankScoreFont = Font("Segoe UI", Font.BOLD, 20)
        private val STRENGTH_ORDER = listOf("ultra", "max", "xhigh", "high", "medium", "low")
        private val MODEL_ORDER = listOf("GPT5.6 Sol", "GPT5.6 Terra", "GPT5.6 Luna", "GPT-6 Astra", "GPT-6 Sol", "GPT-6 Luna", "GPT5.5", "DeepSeek V4 Flash", "DeepSeek V4 Pro", "DeepSeek V4.1 Flash", "DeepSeek V4 Flash DSH", "DeepSeek V4.1 Flash DSH", "DeepSeek V4 Flash Vision DSH")

        private fun color(hex: String): Color = Color(Integer.parseInt(hex.removePrefix("#"), 16))
    }
}
