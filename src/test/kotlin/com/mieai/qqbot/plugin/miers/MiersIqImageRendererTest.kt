package com.mieai.qqbot.plugin.miers

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MiersIqImageRendererTest {
    @Test
    fun `live model table has 23 models and ranking preserves stable ties`() {
        val models = testModels()
        assertEquals(23, models.size)
        assertEquals(MiersIqImageRenderer.MODEL_COUNT, models.size)

        val ranked = models.withIndex()
            .sortedWith(compareByDescending<IndexedValue<MiersIqModel>> { it.value.iq }.thenBy { it.index })
            .map(IndexedValue<MiersIqModel>::value)
        assertEquals(23, ranked.size)
        assertEquals("GPT5.6 Sol", ranked.first().name)
        assertEquals("max", ranked.first().strength)
        assertEquals(105.8, ranked.first().iq)
        assertEquals("GPT5.6 Luna", ranked.last().name)
        assertEquals("low", ranked.last().strength)
        assertEquals(8.0, ranked.last().iq)

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
    fun `first module groups six models and exposes averages`() {
        val models = testModels()
        val grouped = models.groupBy(MiersIqModel::name)
        val displayed = listOf("GPT5.6 Sol", "GPT5.6 Terra", "GPT5.6 Luna", "GPT5.5", "DeepSeek V4 Flash", "DeepSeek V4 Pro")
        assertEquals(displayed, grouped.keys.toList())
        assertEquals(listOf("ultra", "max", "xhigh", "high", "medium", "low"), grouped.getValue("GPT5.6 Sol").map(MiersIqModel::strength))
        assertEquals(93.5333333333, grouped.getValue("GPT5.6 Sol").map(MiersIqModel::iq).average(), 0.0001)
        assertEquals(59.44, grouped.getValue("GPT5.6 Luna").map(MiersIqModel::iq).average(), 0.0001)
    }

    @Test
    fun `rendered output is a non-empty 1400 by 1750 png with bars and guides`() {
        val png = MiersIqImageRenderer(testModels()).renderPng()
        assertEquals(
            listOf(137, 80, 78, 71, 13, 10, 26, 10),
            png.take(8).map { it.toInt() and 0xFF },
        )

        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
        assertEquals(1400, image.width)
        assertEquals(1750, image.height)
        assertTrue(hasNonBackgroundPixel(image))

        // First ranked bar is Sol blue: the filled segment is intentionally visible.
        assertEquals(0x58A6FF, image.getRGB(500, 454) and 0x00FFFFFF)

        // The 20-point guide at x=444 is drawn above the background before the rows.
        assertTrue((432..451).any { y -> image.getRGB(444, y) != image.getRGB(0, 0) })
    }

    @Test
    fun `model cards render separate thinking strength cells`() {
        val image = ImageIO.read(ByteArrayInputStream(MiersIqImageRenderer(testModels()).renderPng()))
        val cardBackground = image.getRGB(100, 170)
        val firstCell = image.getRGB(50, 220)
        val horizontalGap = image.getRGB(108, 220)
        val secondColumn = image.getRGB(120, 220)
        val gap = image.getRGB(60, 247)
        val secondCell = image.getRGB(50, 293)

        assertTrue(firstCell != cardBackground)
        assertEquals(cardBackground, horizontalGap)
        assertEquals(firstCell, secondColumn)
        assertEquals(cardBackground, gap)
        assertEquals(firstCell, secondCell)
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
        MiersIqModel("GPT5.5", 100.4, MiersModelFamily.GPT55, "xhigh"),
        MiersIqModel("GPT5.5", 84.4, MiersModelFamily.GPT55, "high"),
        MiersIqModel("DeepSeek V4 Flash", 92.4, MiersModelFamily.DEEPSEEK, "max"),
        MiersIqModel("DeepSeek V4 Flash", 73.7, MiersModelFamily.DEEPSEEK, "high"),
        MiersIqModel("DeepSeek V4 Pro", 99.2, MiersModelFamily.DEEPSEEK, "max"),
        MiersIqModel("DeepSeek V4 Pro", 90.8, MiersModelFamily.DEEPSEEK, "high"),
    )
}
