package com.mieai.qqbot.plugin.miers

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.plugin.api.GroupMemberRole
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MediaKind
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import com.mieai.qqbot.plugin.testkit.PluginTestContext
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MiersPluginFactoryTest {
    @Test
    fun `factory has a public no arg constructor and is discoverable by service loader`() {
        assertEquals("miers", MiersPluginFactory::class.java.getConstructor().newInstance().pluginId)
        assertTrue(ServiceLoader.load(BotPluginFactory::class.java).any { it.pluginId == "miers" })
    }

    @Test
    fun `help lists every command and lifecycle is idempotent`() {
        withPlugin(defaultConfiguration()) { fixture, factory, plugin ->
            assertEquals("miers", factory.pluginId)
            plugin.start()
            assertEquals(setOf("miers-commands"), fixture.events.handlerIds())

            fixture.events.emit(event(fixture, "  /MIERS   HELP  ")).toCompletableFuture().join()

            val help = fixture.messages.textMessages().single().content
            assertTrue(help.contains("/miers -"))
            assertTrue(help.contains("实时抓取"))
            assertTrue(help.contains("/miers help -"))
            assertTrue(help.contains("/miers st -"))

            fixture.events.emit(event(fixture, "/miers unknown")).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("/miers help"))

            plugin.stop()
            plugin.stop()
            assertTrue(fixture.events.handlerIds().isEmpty())
        }
    }

    @Test
    fun `configured aliases trigger every command and preserve canonical behavior`() {
        val configuration = defaultConfiguration().copy(
            commandAliases = MiersCommandAliases(
                query = "模型IQ",
                help = "查询miers指令",
                toggle = "切换miers状态",
            ),
        )
        withPlugin(configuration) { fixture, _, _ ->
            fixture.events.emit(event(fixture, "/查询miers指令")).toCompletableFuture().join()
            val aliasHelp = fixture.messages.textMessages().single().content
            assertTrue(aliasHelp.contains("/miers（别名：/模型IQ）"))
            assertTrue(aliasHelp.contains("/miers help（别名：/查询miers指令）"))
            assertTrue(aliasHelp.contains("/miers st（别名：/切换miers状态）"))

            fixture.events.emit(event(fixture, "/模型IQ")).toCompletableFuture().join()
            awaitCondition { fixture.media.uploads().size == 1 }

            fixture.events.emit(event(fixture, "/miers")).toCompletableFuture().join()
            awaitCondition { fixture.media.uploads().size == 2 }

            fixture.events.emit(
                event(
                    fixture,
                    "/切换miers状态",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-alias",
                    role = GroupMemberRole.MEMBER,
                ),
            ).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("管理员或群主"))
            assertFalse(readConfiguration(fixture).isGroupBlocked("group-alias"))

            fixture.events.emit(
                event(
                    fixture,
                    "/切换miers状态",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-alias",
                    role = GroupMemberRole.ADMIN,
                ),
            ).toCompletableFuture().join()
            val blockedConfiguration = readConfiguration(fixture)
            assertTrue(blockedConfiguration.isGroupBlocked("group-alias"))
            assertEquals(configuration.commandAliases, blockedConfiguration.commandAliases)

            fixture.events.emit(
                event(
                    fixture,
                    "/模型IQ",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-alias",
                    role = GroupMemberRole.MEMBER,
                ),
            ).toCompletableFuture().join()
            assertEquals(2, fixture.media.uploads().size)

            fixture.events.emit(
                event(
                    fixture,
                    "/miers help",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-alias",
                    role = GroupMemberRole.MEMBER,
                ),
            ).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("/查询miers指令"))

            fixture.events.emit(event(fixture, "/查询miers指令 extra")).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("未知 MieRS 指令"))

            fixture.events.emit(
                event(
                    fixture,
                    "/切换miers状态",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-alias",
                    role = GroupMemberRole.OWNER,
                ),
            ).toCompletableFuture().join()
            assertFalse(readConfiguration(fixture).isGroupBlocked("group-alias"))

            fixture.events.emit(
                event(
                    fixture,
                    "/模型IQ",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-alias",
                    role = GroupMemberRole.MEMBER,
                ),
            ).toCompletableFuture().join()
            awaitCondition { fixture.media.uploads().size == 3 }
        }
    }

    @Test
    fun `query stages a png and enqueues it as a passive reply`() {
        withPlugin(defaultConfiguration()) { fixture, _, _ ->
            val source = event(fixture, "/miers")

            fixture.events.emit(source).toCompletableFuture().join()
            awaitCondition { fixture.media.stagedMessages().size == 1 }

            val upload = fixture.media.uploads().single()
            assertEquals(MediaKind.IMAGE, upload.kind)
            assertEquals("miers-iq.png", upload.fileName)
            assertEquals("image/png", upload.contentType)
            assertEquals(PNG_SIGNATURE, upload.data.take(PNG_SIGNATURE.size).map { it.toInt() and 0xFF })

            val outbound = fixture.media.stagedMessages().single()
            assertEquals(source.message?.replyTarget, outbound.target)
            assertNull(outbound.content)
            assertEquals(source.message?.messageId, outbound.replyMessageId)
            assertEquals(source.message?.eventId, outbound.replyEventId)
            assertEquals("miers:${source.id}", outbound.deduplicationKey)
            assertEquals(source.id, outbound.sourceEventId)
            assertTrue(fixture.messages.textMessages().isEmpty())
        }
    }

    @Test
    fun `default query fetches CodexRadar on every accepted command and reports invalid data`() {
        val yaml = MiersConfigurationCodec.render(defaultConfiguration())
        PluginTestContext("miers", yaml, "config.yml").use { fixture ->
            val plugin = MiersPluginFactory().create(fixture.context)
            plugin.start()
            try {
                fixture.events.emit(event(fixture, "/miers", authorId = "user-1")).toCompletableFuture().join()
                awaitCondition { fixture.http.requests().size == 1 && fixture.messages.textMessages().size == 1 }

                fixture.events.emit(event(fixture, "/miers", authorId = "user-2")).toCompletableFuture().join()
                awaitCondition { fixture.http.requests().size == 2 && fixture.messages.textMessages().size == 2 }

                fixture.http.requests().forEach { request ->
                    assertEquals(CodexRadarIqClient.ENDPOINT, request.uri.toString())
                }
                assertTrue(fixture.messages.textMessages().all { it.content.contains("抓取失败") })
                assertTrue(fixture.media.uploads().isEmpty())
                assertEquals(2, fixture.logger.entries().count { it.message.contains("live IQ") })
            } finally {
                plugin.stop()
            }
        }
    }

    @Test
    fun `only group admins and owners can toggle the group and yaml updates immediately`() {
        withPlugin(defaultConfiguration()) { fixture, _, _ ->
            val memberToggle = event(
                fixture,
                "/miers st",
                targetType = MessageTargetType.GROUP,
                targetId = "group-1",
                role = GroupMemberRole.MEMBER,
            )
            fixture.events.emit(memberToggle).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("管理员或群主"))
            assertFalse(readConfiguration(fixture).isGroupBlocked("group-1"))

            val adminToggle = memberToggle.copy(
                id = UUID.randomUUID(),
                platformEventId = "platform-${UUID.randomUUID()}",
                message = memberToggle.message?.copy(memberRole = GroupMemberRole.ADMIN),
            )
            fixture.events.emit(adminToggle).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("已禁用"))
            assertTrue(readConfiguration(fixture).isGroupBlocked("group-1"))

            fixture.events.emit(
                event(
                    fixture,
                    "/miers",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-1",
                    role = GroupMemberRole.MEMBER,
                ),
            ).toCompletableFuture().join()
            assertTrue(fixture.media.uploads().isEmpty())

            fixture.events.emit(
                event(
                    fixture,
                    "/miers help",
                    targetType = MessageTargetType.GROUP,
                    targetId = "group-1",
                    role = GroupMemberRole.MEMBER,
                ),
            ).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("/miers st -"))

            val ownerToggle = event(
                fixture,
                "/miers st",
                targetType = MessageTargetType.GROUP,
                targetId = "group-1",
                role = GroupMemberRole.OWNER,
            )
            fixture.events.emit(ownerToggle).toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().last().content.contains("已启用"))
            assertFalse(readConfiguration(fixture).isGroupBlocked("group-1"))
        }
    }

    @Test
    fun `toggle outside a group is rejected`() {
        withPlugin(defaultConfiguration()) { fixture, _, _ ->
            fixture.events.emit(event(fixture, "/miers st")).toCompletableFuture().join()

            assertTrue(fixture.messages.textMessages().single().content.contains("仅可在群聊"))
            assertTrue(readConfiguration(fixture).blockedGroups.isEmpty())
        }
    }

    @Test
    fun `full queue message is sent and rejected request does not consume cooldown`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val fetchCount = AtomicInteger()
        val configuration = MiersConfiguration(
            maxQueue = 1,
            queueFullMessage = "IQ 队列已满",
            cooldownMinutes = 5,
        )

        withPlugin(configuration, modelSupplier = {
            if (fetchCount.incrementAndGet() == 1) {
                firstStarted.countDown()
                check(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "first fetch was not released" }
            }
            completedModels()
        }) { fixture, _, _ ->
            val first = event(fixture, "/miers", authorId = "user-1")
            fixture.events.emit(first).toCompletableFuture().join()
            assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val rejected = event(fixture, "/miers", authorId = "user-2")
            fixture.events.emit(rejected).toCompletableFuture().join()
            assertEquals("IQ 队列已满", fixture.messages.textMessages().single().content)

            releaseFirst.countDown()
            awaitCondition { fixture.media.uploads().size == 1 }

            fixture.events.emit(
                event(fixture, "/miers", authorId = "user-2", receivedAt = rejected.receivedAt.plusSeconds(1)),
            ).toCompletableFuture().join()
            awaitCondition { fixture.media.uploads().size == 2 }
            assertEquals(1, fixture.messages.textMessages().size)
        }
    }

    @Test
    fun `empty full queue message stays silent`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val configuration = MiersConfiguration(maxQueue = 1, queueFullMessage = "", cooldownMinutes = 0)

        withPlugin(configuration, modelSupplier = {
            firstStarted.countDown()
            check(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "fetch was not released" }
            completedModels()
        }) { fixture, _, _ ->
            fixture.events.emit(event(fixture, "/miers", authorId = "user-1")).toCompletableFuture().join()
            assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            fixture.events.emit(event(fixture, "/miers", authorId = "user-2")).toCompletableFuture().join()

            assertTrue(fixture.messages.textMessages().isEmpty())
            assertEquals(0, fixture.media.uploads().size)
            releaseFirst.countDown()
            awaitCondition { fixture.media.uploads().size == 1 }
        }
    }

    @Test
    fun `cooldown is per user and expires at the configured minute`() {
        val configuration = MiersConfiguration(maxQueue = 10, cooldownMinutes = 5)
        val start = Instant.parse("2026-08-05T12:00:00Z")
        withPlugin(configuration) { fixture, _, _ ->
            fixture.events.emit(event(fixture, "/miers", authorId = "user-1", receivedAt = start))
                .toCompletableFuture().join()
            awaitCondition { fixture.media.uploads().size == 1 }

            fixture.events.emit(event(fixture, "/miers", authorId = "user-1", receivedAt = start.plusSeconds(61)))
                .toCompletableFuture().join()
            assertTrue(fixture.messages.textMessages().single().content.contains("4 分钟"))
            assertEquals(1, fixture.media.uploads().size)

            fixture.events.emit(event(fixture, "/miers", authorId = "user-2", receivedAt = start.plusSeconds(61)))
                .toCompletableFuture().join()
            awaitCondition { fixture.media.uploads().size == 2 }

            fixture.events.emit(event(fixture, "/miers", authorId = "user-1", receivedAt = start.plusSeconds(300)))
                .toCompletableFuture().join()
            awaitCondition { fixture.media.uploads().size == 3 }
        }
    }

    private fun withPlugin(
        configuration: MiersConfiguration,
        modelSupplier: () -> CompletionStage<List<MiersIqModel>> = ::completedModels,
        test: (PluginTestContext, MiersPluginFactory, BotPlugin) -> Unit,
    ) {
        val yaml = MiersConfigurationCodec.render(configuration)
        PluginTestContext("miers", yaml, "config.yml").use { fixture ->
            val factory = MiersPluginFactory.withModelSupplier(modelSupplier)
            val plugin = factory.create(fixture.context)
            plugin.start()
            try {
                test(fixture, factory, plugin)
            } finally {
                plugin.stop()
            }
        }
    }

    private fun defaultConfiguration(): MiersConfiguration = MiersConfiguration.defaults()

    private fun readConfiguration(fixture: PluginTestContext): MiersConfiguration =
        MiersConfigurationCodec.parse(Files.readString(fixture.context.configurationFile))

    private fun event(
        fixture: PluginTestContext,
        content: String?,
        targetType: MessageTargetType = MessageTargetType.C2C,
        targetId: String = "user-1",
        role: GroupMemberRole? = null,
        authorId: String? = "user-1",
        receivedAt: Instant = Instant.parse("2026-08-05T12:00:00Z"),
    ): PluginEvent = PluginEvent(
        id = UUID.randomUUID(),
        botId = fixture.context.base.botId,
        environment = BotEnvironment.SANDBOX,
        eventType = if (targetType == MessageTargetType.GROUP) "GROUP_MESSAGE_CREATE" else "C2C_MESSAGE_CREATE",
        platformEventId = "platform-${UUID.randomUUID()}",
        rawPayload = "{}",
        receivedAt = receivedAt,
        message = InboundMessage(
            replyTarget = MessageTarget(targetType, targetId),
            messageId = "message-${UUID.randomUUID()}",
            eventId = "event-${UUID.randomUUID()}",
            authorId = authorId,
            content = content,
            memberRole = role,
        ),
    )

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("condition was not met within ${TIMEOUT_SECONDS}s")
            Thread.sleep(5)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L

        val PNG_SIGNATURE = listOf(137, 80, 78, 71, 13, 10, 26, 10)

        val SAMPLE_MODELS: List<MiersIqModel> = List(MiersIqImageRenderer.MODEL_COUNT) { index ->
            val family = MiersModelFamily.entries[index % MiersModelFamily.entries.size]
            val strength = MiersIqModel.STRENGTHS.elementAt(index % MiersIqModel.STRENGTHS.size)
            MiersIqModel(
                name = "Test Model ${index + 1}",
                iq = 20.0 + (index * 4.0),
                family = family,
                strength = strength,
            )
        }

        fun completedModels(): CompletionStage<List<MiersIqModel>> =
            CompletableFuture.completedFuture(SAMPLE_MODELS)
    }
}
