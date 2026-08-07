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
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class MiersIqModel(
    val name: String,
    val iq: Double,
    val family: MiersModelFamily,
    val strength: String,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(iq in 0.0..MiersIqImageRenderer.IQ_SCALE_MAX) { "iq must be within the image scale" }
        require(strength in STRENGTHS) { "strength is unsupported" }
    }

    companion object {
        val STRENGTHS: Set<String> = setOf("ultra", "max", "xhigh", "high", "medium", "low")
    }
}

enum class MiersModelFamily(val rgb: Int) {
    SOL(0x58A6FF),
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
    init {
        require(models.size == MODEL_COUNT) { "the live IQ table must contain exactly " + MODEL_COUNT + " models" }
    }

    fun renderPng(): ByteArray {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            prepare(graphics)
            graphics.color = BACKGROUND
            graphics.fillRect(0, 0, WIDTH, HEIGHT)
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

    private fun drawHeader(graphics: Graphics2D) {
        drawText(graphics, "CODEXRADAR / INTELLIGENCE EFFICIENCY", eyebrowFont, MUTED, 32, 36)
        drawText(graphics, "21 个模型档位 IQ", titleFont, TEXT, 30, 76)
        drawText(graphics, "7 x 3 GRID", eyebrowFont, MUTED, 1280, 42, alignRight = true)
        drawText(graphics, "实时数据", metaFont, MUTED, 1368, 68, alignRight = true)
    }

    private fun drawGrid(graphics: Graphics2D) {
        models.forEachIndexed { index, model ->
            val column = index % GRID_COLUMNS
            val row = index / GRID_COLUMNS
            val x = GRID_MARGIN_X + (column * (CARD_WIDTH + CARD_GAP_X))
            val y = GRID_Y + (row * (CARD_HEIGHT + CARD_GAP_Y))
            val accent = model.family.color()

            graphics.color = SURFACE
            graphics.fillRoundRect(x, y, CARD_WIDTH, CARD_HEIGHT, CARD_RADIUS, CARD_RADIUS)
            graphics.color = BORDER
            graphics.stroke = BasicStroke(1f)
            graphics.drawRoundRect(x, y, CARD_WIDTH, CARD_HEIGHT, CARD_RADIUS, CARD_RADIUS)
            graphics.color = accent
            graphics.fillRect(x, y + 16, 4, 44)

            val badgeWidth = badgeWidth(graphics, model.strength, strengthFont, CARD_BADGE_MIN_WIDTH)
            val badgeX = x + CARD_WIDTH - CARD_PADDING - badgeWidth
            drawOutlinedPill(graphics, model.strength, strengthFont, accent, badgeX, y + 10, badgeWidth, CARD_BADGE_HEIGHT)

            val nameX = x + CARD_PADDING
            val nameWidth = max(30, badgeX - nameX - 8)
            drawWrappedText(graphics, model.name, nameFont, TEXT, nameX, y + 31, nameWidth, 2)

            val score = formatIq(model.iq)
            drawText(graphics, score, valueFont, TEXT, x + CARD_PADDING - 2, y + 110)
            val scoreWidth = graphics.getFontMetrics(valueFont).stringWidth(score)
            drawText(graphics, "IQ", unitFont, MUTED, x + CARD_PADDING + scoreWidth + 3, y + 108)

            val barX = x + CARD_PADDING
            val barY = y + 134
            val barWidth = CARD_WIDTH - (CARD_PADDING * 2)
            graphics.color = TRACK
            graphics.fillRect(barX, barY, barWidth, 5)
            graphics.color = accent
            graphics.fillRect(barX, barY, fillWidth(barWidth, model.iq), 5)
        }
    }

    private fun drawRanking(graphics: Graphics2D) {
        val ranked = models.withIndex()
            .sortedWith(compareByDescending<IndexedValue<MiersIqModel>> { it.value.iq }.thenBy { it.index })
            .map(IndexedValue<MiersIqModel>::value)

        drawText(graphics, "模型排行", titleFont, TEXT, 30, 679)
        drawText(graphics, "按 IQ 从高到低 · 同分保持原始顺序", metaFont, MUTED, 245, 674)

        val axisTopY = RANKING_START_Y - 4
        val axisBottomY = RANKING_START_Y + (ranked.lastIndex * RANK_ROW_HEIGHT) + 33
        TICKS.forEach { tick ->
            val x = RANK_BAR_X + ((RANK_BAR_WIDTH * tick) / IQ_SCALE_MAX).roundToInt()
            graphics.color = AXIS
            graphics.stroke = BasicStroke(1f)
            graphics.drawLine(x, axisTopY, x, axisBottomY)
            drawText(graphics, tick.toString(), rankTickFont, MUTED, x, RANKING_START_Y - 17, centered = true)
        }

        ranked.forEachIndexed { index, model ->
            val y = RANKING_START_Y + (index * RANK_ROW_HEIGHT)
            val accent = model.family.color()
            drawOutlinedPill(graphics, (index + 1).toString(), unitFont, accent, 30, y + 7, 32, 27)
            val label = model.name + " " + model.strength
            drawText(graphics, ellipsize(graphics.getFontMetrics(nameFont), label, RANK_BAR_X - RANK_NAME_X - 14), nameFont, TEXT, RANK_NAME_X, y + 22)

            graphics.color = TRACK
            graphics.fillRect(RANK_BAR_X, y + 18, RANK_BAR_WIDTH, 7)
            graphics.color = accent
            graphics.fillRect(RANK_BAR_X, y + 18, fillWidth(RANK_BAR_WIDTH, model.iq), 7)
            drawText(graphics, formatIq(model.iq), rankScoreFont, TEXT, RANK_VALUE_X, y + 32)
        }
    }

    private fun drawFooter(graphics: Graphics2D) {
        graphics.color = BORDER
        graphics.stroke = BasicStroke(1f)
        graphics.drawLine(32, FOOTER_Y, WIDTH - 32, FOOTER_Y)
        drawText(graphics, "IQ 参考尺度 0-120", metaFont, MUTED, 32, FOOTER_Y + 33)
        drawText(graphics, "来源 codexradar.com | 实时抓取", metaFont, MUTED, WIDTH - 32, FOOTER_Y + 33, alignRight = true)
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

    private fun drawWrappedText(
        graphics: Graphics2D,
        text: String,
        font: Font,
        color: Color,
        x: Int,
        baselineY: Int,
        maxWidth: Int,
        maxLines: Int,
    ) {
        val metrics = fontMetrics(graphics, font)
        wrap(metrics, text, maxWidth).take(maxLines).forEachIndexed { index, line ->
            drawText(graphics, line, font, color, x, baselineY + (index * (metrics.height - 1)))
        }
    }

    private fun wrap(metrics: FontMetrics, text: String, maxWidth: Int): List<String> {
        if (metrics.stringWidth(text) <= maxWidth) return listOf(text)
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else current + " " + word
            if (current.isNotEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
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

    private fun badgeWidth(graphics: Graphics2D, text: String, font: Font, minimum: Int): Int =
        max(minimum, ceil(fontMetrics(graphics, font).stringWidth(text) + 12.0).toInt())

    private fun fontMetrics(graphics: Graphics2D, font: Font): FontMetrics {
        graphics.font = font
        return graphics.fontMetrics
    }

    private fun fillWidth(width: Int, iq: Double): Int =
        max(3, (width * (iq / IQ_SCALE_MAX)).roundToInt())

    private fun formatIq(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    companion object {
        const val WIDTH: Int = 1400
        const val HEIGHT: Int = 1750
        const val MODEL_COUNT: Int = 21
        const val IQ_SCALE_MAX: Double = 120.0

        private const val GRID_COLUMNS = 7
        private const val GRID_MARGIN_X = 32
        private const val GRID_Y = 110
        private const val CARD_WIDTH = 184
        private const val CARD_HEIGHT = 160
        private const val CARD_RADIUS = 7
        private const val CARD_GAP_X = 8
        private const val CARD_GAP_Y = 10
        private const val CARD_PADDING = 14
        private const val CARD_BADGE_HEIGHT = 22
        private const val CARD_BADGE_MIN_WIDTH = 38
        private const val RANKING_START_Y = 715
        private const val RANK_ROW_HEIGHT = 43
        private const val RANK_NAME_X = 76
        private const val RANK_BAR_X = 300
        private const val RANK_BAR_WIDTH = 865
        private const val RANK_VALUE_X = 1204
        private const val FOOTER_Y = 1640

        private val TICKS = listOf(0, 20, 40, 60, 80, 100, 120)
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
        private val nameFont = Font("Segoe UI", Font.PLAIN, 15)
        private val strengthFont = Font("Segoe UI", Font.PLAIN, 10)
        private val rankTickFont = Font("Segoe UI", Font.PLAIN, 14)
        private val valueFont = Font("Segoe UI", Font.BOLD, 42)
        private val unitFont = Font("Segoe UI", Font.PLAIN, 13)
        private val rankScoreFont = Font("Segoe UI", Font.BOLD, 30)

        private fun color(hex: String): Color = Color(Integer.parseInt(hex.removePrefix("#"), 16))
    }
}
