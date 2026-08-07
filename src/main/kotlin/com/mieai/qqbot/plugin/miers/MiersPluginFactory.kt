package com.mieai.qqbot.plugin.miers

import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.GroupMemberRole
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MediaKind
import com.mieai.qqbot.plugin.api.MediaUpload
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.api.StagedMediaMessage
import com.mieai.qqbot.plugin.api.TextMessage
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import kotlin.math.max

/** ServiceLoader entry point for the MieRS model-IQ plugin. */
class MiersPluginFactory private constructor(
    private val modelSupplierFactory: (PluginRuntimeContext) -> () -> CompletionStage<List<MiersIqModel>>,
) : BotPluginFactory {
    constructor() : this({ context -> CodexRadarIqClient(context.httpClient)::fetch })

    override val pluginId: String = PLUGIN_ID

    override fun create(context: PluginRuntimeContext): BotPlugin {
        val configurationStore = MiersConfigurationStore.open(
            context.configurationFile,
            context.configuration.content,
        )
        return MiersPlugin(context, configurationStore, modelSupplierFactory(context))
    }

    companion object {
        const val PLUGIN_ID: String = "miers"

        internal fun withModelSupplier(
            modelSupplier: () -> CompletionStage<List<MiersIqModel>>,
        ): MiersPluginFactory = MiersPluginFactory { modelSupplier }
    }
}

private class MiersPlugin(
    private val context: PluginRuntimeContext,
    private val configurationStore: MiersConfigurationStore,
    private val modelSupplier: () -> CompletionStage<List<MiersIqModel>>,
) : BotPlugin {
    private val lifecycleLock = Any()
    private val cooldownTracker = MiersCooldownTracker()
    private val requestQueue = MiersRequestQueue(configurationStore.snapshot().maxQueue) { failure ->
        context.base.logger.error("MieRS IQ request failed", failure)
    }

    @Volatile
    private var stopped = false
    private var subscription: EventSubscription? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (stopped || subscription?.isActive == true) return
            subscription = context.events.subscribe(HANDLER_ID, MESSAGE_EVENT_TYPES, ::handleMessage)
        }
    }

    override fun stop() {
        val activeSubscription = synchronized(lifecycleLock) {
            if (stopped) return
            stopped = true
            subscription.also { subscription = null }
        }
        activeSubscription?.close()
        requestQueue.close()
        cooldownTracker.clear()
    }

    private fun handleMessage(event: PluginEvent): CompletionStage<Void> {
        if (stopped) return completed()
        val inbound = event.message ?: return completed()
        return when (parseCommand(inbound.content)) {
            MiersCommand.QUERY -> handleIqQuery(event, inbound)
            MiersCommand.HELP -> reply(event, HELP_TEXT)
            MiersCommand.TOGGLE -> handleGroupToggle(event, inbound)
            MiersCommand.UNKNOWN -> reply(event, UNKNOWN_COMMAND_TEXT)
            null -> completed()
        }
    }

    private fun handleIqQuery(event: PluginEvent, inbound: InboundMessage): CompletionStage<Void> {
        if (inbound.replyTarget.type == MessageTargetType.GROUP &&
            configurationStore.isGroupBlocked(inbound.replyTarget.id)
        ) {
            return completed()
        }

        val configuration = configurationStore.snapshot()
        val decision = cooldownTracker.reserve(
            inbound.authorId,
            event.receivedAt,
            configuration.cooldownMinutes,
        )
        if (decision is MiersCooldownDecision.Limited) {
            return reply(event, cooldownMessage(decision.remaining))
        }

        val accepted = decision as MiersCooldownDecision.Accepted
        if (requestQueue.submit { enqueueIqImage(event, inbound) }) {
            return completed()
        }

        cooldownTracker.rollback(accepted)
        return configuration.queueFullMessage.takeIf(String::isNotBlank)
            ?.let { message -> reply(event, message) }
            ?: completed()
    }

    private fun handleGroupToggle(event: PluginEvent, inbound: InboundMessage): CompletionStage<Void> {
        if (inbound.replyTarget.type != MessageTargetType.GROUP) {
            return reply(event, GROUP_ONLY_TEXT)
        }
        if (inbound.memberRole != GroupMemberRole.ADMIN && inbound.memberRole != GroupMemberRole.OWNER) {
            return reply(event, ADMIN_ONLY_TEXT)
        }

        val nowBlocked = try {
            configurationStore.toggleGroup(inbound.replyTarget.id)
        } catch (failure: Exception) {
            context.base.logger.error("MieRS group setting could not be persisted", failure)
            return reply(event, SETTING_FAILURE_TEXT)
        }
        return reply(event, if (nowBlocked) GROUP_BLOCKED_TEXT else GROUP_ENABLED_TEXT)
    }

    private fun enqueueIqImage(event: PluginEvent, inbound: InboundMessage): CompletionStage<*> {
        val imageStage = try {
            modelSupplier().thenApply { models -> MiersIqImageRenderer(models).renderPng() }
        } catch (failure: Throwable) {
            return handleIqQueryFailure(event, failure)
        }
        return imageStage.handle<CompletionStage<*>> { image, failure ->
            if (failure != null) {
                handleIqQueryFailure(event, failure)
            } else {
                enqueueRenderedImage(event, inbound, image)
            }
        }.thenCompose { stage -> stage }
    }

    private fun enqueueRenderedImage(
        event: PluginEvent,
        inbound: InboundMessage,
        image: ByteArray,
    ): CompletionStage<*> {
        val upload = MediaUpload(
            MediaKind.IMAGE,
            IMAGE_FILE_NAME,
            IMAGE_CONTENT_TYPE,
            image,
        )
        return context.mediaService.stage(upload).thenCompose { staged ->
            context.mediaService.enqueue(
                StagedMediaMessage(
                    target = inbound.replyTarget,
                    media = staged,
                    content = null,
                    replyMessageId = inbound.messageId,
                    replyEventId = inbound.eventId,
                    messageSequence = 1,
                    deduplicationKey = "miers:${event.id}",
                    sourceEventId = event.id,
                ),
            )
        }
    }

    private fun handleIqQueryFailure(event: PluginEvent, failure: Throwable): CompletionStage<Void> {
        context.base.logger.error("MieRS live IQ fetch or render failed", unwrapCompletionFailure(failure))
        return reply(event, IQ_QUERY_FAILURE_TEXT)
    }

    private fun unwrapCompletionFailure(failure: Throwable): Throwable {
        var cause = failure
        while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) {
            cause = cause.cause!!
        }
        return cause
    }

    private fun reply(event: PluginEvent, message: String): CompletionStage<Void> =
        context.base.messageSender.enqueue(TextMessage.reply(event, message)).thenApply<Void> { null }

    private fun completed(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    private fun parseCommand(content: String?): MiersCommand? {
        val tokens = content?.trim()?.split(COMMAND_WHITESPACE)?.filter(String::isNotEmpty).orEmpty()
        if (tokens.isEmpty() || !tokens.first().equals(ROOT_COMMAND, ignoreCase = true)) return null
        return when {
            tokens.size == 1 -> MiersCommand.QUERY
            tokens.size == 2 && tokens[1].lowercase(Locale.ROOT) == "help" -> MiersCommand.HELP
            tokens.size == 2 && tokens[1].lowercase(Locale.ROOT) == "st" -> MiersCommand.TOGGLE
            else -> MiersCommand.UNKNOWN
        }
    }

    private fun cooldownMessage(remaining: Duration): String {
        val roundedMinutes = max(1L, (remaining.seconds + 59L) / 60L)
        return "使用过于频繁，请在 $roundedMinutes 分钟后再使用 /miers。"
    }

    private enum class MiersCommand {
        QUERY,
        HELP,
        TOGGLE,
        UNKNOWN,
    }

    private companion object {
        const val HANDLER_ID = "miers-commands"
        const val ROOT_COMMAND = "/miers"
        const val IMAGE_FILE_NAME = "miers-iq.png"
        const val IMAGE_CONTENT_TYPE = "image/png"
        const val HELP_TEXT = """/miers - 实时抓取并发送 21 个模型档位的 IQ 图片
/miers help - 查看本插件全部指令及作用
/miers st - 群管理员或群主切换本群是否禁用 /miers 查询"""
        const val IQ_QUERY_FAILURE_TEXT = "CodexRadar IQ 数据抓取失败，请稍后重试。"
        const val UNKNOWN_COMMAND_TEXT = "未知 MieRS 指令，请使用 /miers help 查看可用指令。"
        const val GROUP_ONLY_TEXT = "/miers st 仅可在群聊中使用。"
        const val ADMIN_ONLY_TEXT = "只有本群管理员或群主可以使用 /miers st。"
        const val SETTING_FAILURE_TEXT = "群设置保存失败，当前状态未变更。"
        const val GROUP_BLOCKED_TEXT = "已禁用本群的 /miers 查询。"
        const val GROUP_ENABLED_TEXT = "已启用本群的 /miers 查询。"

        val COMMAND_WHITESPACE = Regex("\\s+")
        val MESSAGE_EVENT_TYPES = setOf(
            "MESSAGE_CREATE",
            "AT_MESSAGE_CREATE",
            "DIRECT_MESSAGE_CREATE",
            "GROUP_AT_MESSAGE_CREATE",
            "GROUP_MESSAGE_CREATE",
            "C2C_MESSAGE_CREATE",
        )
    }
}
