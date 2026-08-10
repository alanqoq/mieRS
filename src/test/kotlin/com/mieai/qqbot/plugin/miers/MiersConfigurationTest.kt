package com.mieai.qqbot.plugin.miers

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiersConfigurationTest {
    @Test
    fun `valid yaml parses all fields`() {
        val parsed = MiersConfigurationCodec.parse(
            """
            blockedGroups:
              - "group-b"
              - "group-a"
            maxQueue: 25
            queueFullMessage: "队列已满，请稍后再试"
            cooldownMinutes: 3
            commandAliases:
              query: "模型IQ"
              help: "查询miers指令"
              toggle: "切换miers状态"
            """.trimIndent(),
        )

        assertEquals(setOf("group-a", "group-b"), parsed.blockedGroups)
        assertEquals(25, parsed.maxQueue)
        assertEquals("队列已满，请稍后再试", parsed.queueFullMessage)
        assertEquals(3, parsed.cooldownMinutes)
        assertEquals(
            MiersCommandAliases("模型IQ", "查询miers指令", "切换miers状态"),
            parsed.commandAliases,
        )
        assertTrue(parsed.isGroupBlocked("group-a"))
        assertFalse(parsed.isGroupBlocked("group-c"))
    }

    @Test
    fun `defaults and legacy yaml use no command aliases`() {
        assertEquals(MiersCommandAliases(), MiersConfiguration.defaults().commandAliases)

        val legacy = MiersConfigurationCodec.parse(
            """
            blockedGroups: []
            maxQueue: 10
            queueFullMessage: ""
            cooldownMinutes: 0
            """.trimIndent(),
        )

        assertEquals(MiersCommandAliases(), legacy.commandAliases)
    }

    @Test
    fun `command aliases render and reject invalid or ambiguous names`() {
        val configuration = MiersConfiguration(
            commandAliases = MiersCommandAliases(
                query = "模型IQ",
                help = "查询miers指令",
                toggle = "切换miers状态",
            ),
        )
        val rendered = MiersConfigurationCodec.render(configuration)
        assertEquals(configuration.immutableCopy(), MiersConfigurationCodec.parse(rendered))
        assertTrue(rendered.contains("query: \"模型IQ\""))
        assertTrue(rendered.contains("help: \"查询miers指令\""))
        assertTrue(rendered.contains("toggle: \"切换miers状态\""))
        assertTrue(rendered.contains("填写时不要带开头的 /"))
        assertTrue(rendered.contains("空字符串表示不启用别名"))
        assertTrue(rendered.contains("/miers help：显示本插件全部指令及作用"))

        val invalidDocuments = listOf(
            rendered.replace("query: \"模型IQ\"", "query: \"/模型IQ\""),
            rendered.replace("query: \"模型IQ\"", "query: \"模型 IQ\""),
            rendered.replace("help: \"查询miers指令\"", "help: \"模型iq\""),
            rendered.replace("query: \"模型IQ\"", "query: \"miers\""),
            rendered.replace("query: \"模型IQ\"", "query: \"${"别".repeat(65)}\""),
            rendered.replace("  toggle: \"切换miers状态\"\n", ""),
            rendered.replace("  toggle: \"切换miers状态\"", "  toggle: \"切换miers状态\"\n  unknown: \"x\""),
        )
        invalidDocuments.forEach { content ->
            assertFailsWith<MiersConfigurationException> {
                MiersConfigurationCodec.parse(content)
            }
        }
        assertFailsWith<MiersConfigurationException> {
            MiersCommandAliases(query = "İ", help = "i")
        }
    }

    @Test
    fun `parser rejects unknown missing duplicate and incorrectly typed fields`() {
        val valid = MiersConfigurationCodec.render(MiersConfiguration.defaults())
        val invalidDocuments = listOf(
            valid.replace("maxQueue: 10", "maxQueue: 10\nunknown: true"),
            valid.replace("cooldownMinutes: 0\n", ""),
            valid.replace("maxQueue: 10", "maxQueue: \"10\""),
            valid + "maxQueue: 10\n",
            valid.replace("blockedGroups: []", "blockedGroups: [\"group-a\", \"group-a\"]"),
            valid.replace("cooldownMinutes: 0", "cooldownMinutes: -1"),
        )

        invalidDocuments.forEach { content ->
            assertFailsWith<MiersConfigurationException> {
                MiersConfigurationCodec.parse(content)
            }
        }
    }

    @Test
    fun `toggle adds and removes group and persists yaml`(@TempDir directory: Path) {
        val configurationFile = directory.resolve("config.yml")
        val initial = MiersConfiguration(
            commandAliases = MiersCommandAliases(
                query = "模型IQ",
                help = "查询miers指令",
                toggle = "切换miers状态",
            ),
        )
        Files.writeString(configurationFile, MiersConfigurationCodec.render(initial))
        val store = MiersConfigurationStore.load(configurationFile)

        assertTrue(store.toggleGroup("group-123"))
        assertTrue(store.isGroupBlocked("group-123"))
        val enabledYaml = Files.readString(configurationFile)
        assertTrue(enabledYaml.contains("- \"group-123\""))
        assertTrue(enabledYaml.contains("# 禁用 /miers IQ 图片查询的群 ID 列表"))
        assertTrue(enabledYaml.contains("# 最大已接收 IQ 请求数"))
        assertTrue(enabledYaml.contains("# 队列已满时发送的提示"))
        assertTrue(enabledYaml.contains("# 同一用户两次成功进入 IQ 请求队列的间隔分钟数"))
        assertTrue(enabledYaml.contains("# 指令别名配置"))
        assertTrue(enabledYaml.contains("# /miers：实时抓取 CodexRadar 数据"))
        assertTrue(enabledYaml.contains("# /miers help：显示本插件全部指令及作用"))
        assertTrue(enabledYaml.contains("# /miers st：仅供群管理员或群主"))
        val enabled = MiersConfigurationCodec.parse(enabledYaml)
        assertTrue(enabled.isGroupBlocked("group-123"))
        assertEquals(initial.commandAliases, enabled.commandAliases)

        assertFalse(store.toggleGroup("group-123"))
        assertFalse(store.isGroupBlocked("group-123"))
        val disabledYaml = Files.readString(configurationFile)
        assertEquals(initial, MiersConfigurationCodec.parse(disabledYaml))
        assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `failed persistence leaves in memory snapshot unchanged`(@TempDir directory: Path) {
        val configurationFile = directory.resolve("config.yml")
        Files.createDirectory(configurationFile)
        val initial = MiersConfiguration.defaults()
        val store = MiersConfigurationStore.open(configurationFile, MiersConfigurationCodec.render(initial))

        assertFailsWith<IOException> {
            store.toggleGroup("group-123")
        }

        assertEquals(initial, store.snapshot())
        assertFalse(store.isGroupBlocked("group-123"))
    }
}
