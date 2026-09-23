package com.mieai.qqbot.plugin.miers

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MiersIqImageRendererTest {
    @Test
    fun `live model table has 29 models and ranking preserves stable ties`() {
        val models = testModels()
        assertEquals(29, models.size)

        val ranked = models.withIndex()
            .sortedWith(compareByDescending<IndexedValue<MiersIqModel>> { it.value.iq }.thenBy { it.index })
            .map(IndexedValue<MiersIqModel>::value)
        assertEquals(29, ranked.size)
        assertEquals("GPT5.6 Sol", ranked.first().name)
        assertEquals("max", ranked.first().strength)
        assertEquals(105.8, ranked.first().iq)
        assertEquals("GPT-6 Astra", ranked.last().name)
        assertEquals("low", ranked.last().strength)
        assertEquals(1.0, ranked.last().iq)

        assertEquals(
            listOf("ultra", "xhigh"),
            ranked.filter { it.iq == 104.5 }.map(MiersIqModel::strength),
        )
        assertEquals(
            listOf("xhigh", "high"),
            ranked.filter { it.iq == 84.4 }.map(MiersIqModel::strength),
        )
        assertTrue(ranked.zipWithNext().all { (left, right) -> left.iq >= right.iq })
    }

    @Test
    fun `first module groups seven models and exposes averages`() {
        val models = testModels()
        val grouped = models.groupBy(MiersIqModel::name)
        val displayed = listOf("GPT5.6 Sol", "GPT5.6 Terra", "GPT5.6 Luna", "GPT-6 Astra", "GPT5.5", "DeepSeek V4 Flash", "DeepSeek V4 Pro")
        assertEquals(displayed, grouped.keys.toList())
        assertEquals(listOf("ultra", "max", "xhigh", "high", "medium", "low"), grouped.getValue("GPT5.6 Sol").map(MiersIqModel::strength))
        assertEquals(93.5333333333, grouped.getValue("GPT5.6 Sol").map(MiersIqModel::iq).average(), 0.0001)
        assertEquals(59.44, grouped.getValue("GPT5.6 Luna").map(MiersIqModel::iq).average(), 0.0001)
        assertEquals(listOf("ultra", "max", "xhigh", "high", "medium", "low"), grouped.getValue("GPT-6 Astra").map(MiersIqModel::strength))
        assertTrue(grouped.getValue("GPT-6 Astra").all { it.family == MiersModelFamily.ASTRA })
    }

    @Test
    fun `rendered output is a non-empty 1210 by 1750 png with visible text bars and guides`() {
        val png = MiersIqImageRenderer(testModels()).renderPng()
        assertEquals(
            listOf(137, 80, 78, 71, 13, 10, 26, 10),
            png.take(8).map { it.toInt() and 0xFF },
        )

        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
        assertEquals(1210, image.width)
        assertEquals(1750, image.height)
        assertTrue(hasNonBackgroundPixel(image))
        assertTrue(hasPixels(image, 960, 50, 1186, 75))
        assertTrue(hasPixels(image, 960, 1660, 1186, 1678))

        val rankStart = 110 + (2 * 242) + 84
        assertEquals(0x58A6FF, image.getRGB(210, rankStart + 14) and 0x00FFFFFF)
        assertEquals(0x58A6FF, image.getRGB(800, rankStart + 14) and 0x00FFFFFF)
        assertTrue(hasPixels(image, 542, rankStart + 8, 585, rankStart + 27))
        assertTrue(hasPixels(image, 1132, rankStart + 8, 1178, rankStart + 27))
        assertTrue(image.getRGB(311, rankStart + 20) != image.getRGB(0, 0))
    }

    @Test
    fun `model cards wrap at five and rank columns continue in global IQ order`() {
        val image = ImageIO.read(ByteArrayInputStream(MiersIqImageRenderer(testModels()).renderPng()))
        val cardBackground = image.getRGB(100, 170)
        val firstCell = image.getRGB(50, 220)
        val horizontalGap = image.getRGB(100, 220)
        val secondColumn = image.getRGB(120, 220)
        val gap = image.getRGB(60, 247)
        val secondRowCellHasPixels = (272..329).any { x ->
            (251..315).any { y -> image.getRGB(x, y) != cardBackground }
        }

        assertTrue(firstCell != cardBackground)
        assertEquals(cardBackground, horizontalGap)
        assertEquals(firstCell, secondColumn)
        assertEquals(cardBackground, gap)
        assertTrue(secondRowCellHasPixels)

        assertEquals(image.getRGB(100, 170), image.getRGB(1000, 170))
        assertEquals(image.getRGB(100, 170), image.getRGB(100, 360))
        assertEquals(image.getRGB(100, 120), image.getRGB(1140, 120))
        assertEquals(image.getRGB(1200, 120), image.getRGB(1150, 120))
        val rankStart = 110 + (2 * 242) + 84
        assertEquals(0x58A6FF, image.getRGB(210, rankStart + 14) and 0x00FFFFFF)
        assertEquals(0x58A6FF, image.getRGB(800, rankStart + 14) and 0x00FFFFFF)
    }

    @Test
    fun `odd ranking puts the globally next model at top of right column`() {
        val models = listOf(
            MiersIqModel("First", 90.0, MiersModelFamily.SOL, "high"),
            MiersIqModel("Second", 80.0, MiersModelFamily.TERRA, "high"),
            MiersIqModel("Third", 70.0, MiersModelFamily.LUNA, "high"),
        )
        val image = ImageIO.read(ByteArrayInputStream(MiersIqImageRenderer(models).renderPng()))
        val rankStart = 110 + 242 + 84
        assertEquals(0x58A6FF, image.getRGB(210, rankStart + 14) and 0x00FFFFFF)
        assertEquals(0x6BCB8B, image.getRGB(800, rankStart + 14) and 0x00FFFFFF)
    }

    @Test
    fun `right ranking column keeps the full DeepSeek model name when strength does not fit`() {
        val models = listOf(
            MiersIqModel("First", 90.0, MiersModelFamily.SOL, "high"),
            MiersIqModel("DeepSeek V4 Flash", 80.0, MiersModelFamily.DEEPSEEK, "high"),
        )
        val actual = ImageIO.read(ByteArrayInputStream(MiersIqImageRenderer(models).renderPng()))
        val expected = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = expected.createGraphics()
        try {
            graphics.color = Color(0x0D, 0x11, 0x17)
            graphics.fillRect(0, 0, expected.width, expected.height)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.font = Font("Segoe UI", Font.PLAIN, 12)
            graphics.color = Color(0xF0, 0xF6, 0xFC)
            graphics.drawString("DeepSeek V4 Flash", 670, 455)
        } finally {
            graphics.dispose()
        }
        for (y in 435..460) for (x in 670..799) assertEquals(expected.getRGB(x, y), actual.getRGB(x, y))
    }

    @Test
    fun `one five six and 29 tier images retain dimensions and wrap card rows`() {
        val cards = (1..6).map { MiersIqModel("Model $it", 40.0 + it, MiersModelFamily.SOL, "high") }
        for (count in listOf(1, 5, 6)) {
            val image = ImageIO.read(ByteArrayInputStream(MiersIqImageRenderer(cards.take(count)).renderPng()))
            assertEquals(1210, image.width)
            assertEquals(1750, image.height)
            assertTrue(hasNonBackgroundPixel(image))
            if (count == 5) assertEquals(image.getRGB(100, 120), image.getRGB(1000, 120))
            if (count == 6) assertEquals(image.getRGB(100, 120), image.getRGB(100, 360))
        }
        val tiers = (1..29).map { MiersIqModel("Model $it", it.toDouble(), MiersModelFamily.SOL, "high") }
        val image = ImageIO.read(ByteArrayInputStream(MiersIqImageRenderer(tiers).renderPng()))
        assertEquals(1210, image.width)
        assertEquals(2169, image.height)
        assertTrue(hasPixels(image, 960, 2108, 1186, 2135))
        assertTrue(hasNonBackgroundPixel(image))
    }

    private fun hasNonBackgroundPixel(image: BufferedImage): Boolean {
        val background = image.getRGB(0, 0)
        for (y in 0 until image.height step 8) {
            for (x in 0 until image.width step 8) {
                if (image.getRGB(x, y) != background) return true
            }
        }
        return false
    }

    private fun hasPixels(image: BufferedImage, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val background = image.getRGB(0, 0)
        for (y in top..bottom) for (x in left..right) if (image.getRGB(x, y) != background) return true
        return false
    }

    private fun testModels(): List<MiersIqModel> = listOf(
        MiersIqModel("GPT5.6 Sol", 104.5, MiersModelFamily.SOL, "ultra"),
        MiersIqModel("GPT5.6 Sol", 105.8, MiersModelFamily.SOL, "max"),
        MiersIqModel("GPT5.6 Sol", 104.5, MiersModelFamily.SOL, "xhigh"),
        MiersIqModel("GPT5.6 Sol", 81.7, MiersModelFamily.SOL, "high"),
        MiersIqModel("GPT5.6 Sol", 88.4, MiersModelFamily.SOL, "medium"),
        MiersIqModel("GPT5.6 Sol", 76.3, MiersModelFamily.SOL, "low"),
        MiersIqModel("GPT5.6 Terra", 97.8, MiersModelFamily.TERRA, "ultra"),
        MiersIqModel("GPT5.6 Terra", 95.1, MiersModelFamily.TERRA, "max"),
        MiersIqModel("GPT5.6 Terra", 84.4, MiersModelFamily.TERRA, "xhigh"),
        MiersIqModel("GPT5.6 Terra", 71.0, MiersModelFamily.TERRA, "high"),
        MiersIqModel("GPT5.6 Terra", 61.6, MiersModelFamily.TERRA, "medium"),
        MiersIqModel("GPT5.6 Terra", 44.2, MiersModelFamily.TERRA, "low"),
        MiersIqModel("GPT5.6 Luna", 101.8, MiersModelFamily.LUNA, "max"),
        MiersIqModel("GPT5.6 Luna", 89.7, MiersModelFamily.LUNA, "xhigh"),
        MiersIqModel("GPT5.6 Luna", 65.6, MiersModelFamily.LUNA, "high"),
        MiersIqModel("GPT5.6 Luna", 32.1, MiersModelFamily.LUNA, "medium"),
        MiersIqModel("GPT5.6 Luna", 8.0, MiersModelFamily.LUNA, "low"),
        MiersIqModel("GPT-6 Astra", 7.0, MiersModelFamily.ASTRA, "ultra"),
        MiersIqModel("GPT-6 Astra", 6.0, MiersModelFamily.ASTRA, "max"),
        MiersIqModel("GPT-6 Astra", 5.0, MiersModelFamily.ASTRA, "xhigh"),
        MiersIqModel("GPT-6 Astra", 4.0, MiersModelFamily.ASTRA, "high"),
        MiersIqModel("GPT-6 Astra", 3.0, MiersModelFamily.ASTRA, "medium"),
        MiersIqModel("GPT-6 Astra", 1.0, MiersModelFamily.ASTRA, "low"),
        MiersIqModel("GPT5.5", 100.4, MiersModelFamily.GPT55, "xhigh"),
        MiersIqModel("GPT5.5", 84.4, MiersModelFamily.GPT55, "high"),
        MiersIqModel("DeepSeek V4 Flash", 92.4, MiersModelFamily.DEEPSEEK, "max"),
        MiersIqModel("DeepSeek V4 Flash", 73.7, MiersModelFamily.DEEPSEEK, "high"),
        MiersIqModel("DeepSeek V4 Pro", 99.2, MiersModelFamily.DEEPSEEK, "max"),
        MiersIqModel("DeepSeek V4 Pro", 90.8, MiersModelFamily.DEEPSEEK, "high"),
    )
}
