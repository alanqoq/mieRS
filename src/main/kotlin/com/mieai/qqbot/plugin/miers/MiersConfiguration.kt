package com.mieai.qqbot.plugin.miers

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Binding-scoped configuration for the MieRS IQ command. */
data class MiersConfiguration(
    val blockedGroups: Set<String> = emptySet(),
    val maxQueue: Int = DEFAULT_MAX_QUEUE,
    val queueFullMessage: String = "",
    val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
    val commandAliases: MiersCommandAliases = MiersCommandAliases(),
) {
    init {
        validateGroupIds(blockedGroups)
        requireConfiguration(maxQueue > 0, "maxQueue", "must be a positive integer")
        requireConfiguration(cooldownMinutes >= 0, "cooldownMinutes", "must be a non-negative integer")
        requireConfiguration(queueFullMessage.codePointCount(0, queueFullMessage.length) <= MAX_MESSAGE_CODE_POINTS, "queueFullMessage", "must not exceed $MAX_MESSAGE_CODE_POINTS Unicode code points")
        requireConfiguration(!hasUnpairedSurrogate(queueFullMessage), "queueFullMessage", "contains an invalid Unicode character")
        requireConfiguration(queueFullMessage.codePoints().noneMatch(::unsupportedControl), "queueFullMessage", "contains an unsupported control character")
    }

    /** Returns true when the IQ query command is disabled for [groupId]. */
    fun isGroupBlocked(groupId: String): Boolean {
        validateGroupId("groupId", groupId)
        return groupId in blockedGroups
    }

    /** Alias used by command handlers that treat the list as a deny-list. */
    fun isBlocked(groupId: String): Boolean = isGroupBlocked(groupId)

    /** Returns an immutable, sorted copy suitable for a store snapshot. */
    fun immutableCopy(): MiersConfiguration = copy(
        blockedGroups = immutableSortedSet(blockedGroups),
    )

    companion object {
        const val DEFAULT_MAX_QUEUE: Int = 10
        const val DEFAULT_COOLDOWN_MINUTES: Int = 0
        const val MAX_MESSAGE_CODE_POINTS: Int = 4_000

        @JvmStatic
        fun defaults(): MiersConfiguration = MiersConfiguration().immutableCopy()

        @JvmStatic
        fun parse(content: String): MiersConfiguration = MiersConfigurationCodec.parse(content)

        @JvmStatic
        fun render(configuration: MiersConfiguration): String = MiersConfigurationCodec.render(configuration)
    }
}

/** Optional standalone command names that supplement the canonical MieRS commands. */
data class MiersCommandAliases(
    val query: String = "",
    val help: String = "",
    val toggle: String = "",
) {
    init {
        val aliases = listOf(
            "query" to query,
            "help" to help,
            "toggle" to toggle,
        )
        val configuredAliases = ArrayList<String>()
        aliases.forEach { (name, alias) ->
            validateCommandAlias("commandAliases.$name", alias)
            if (alias.isNotEmpty()) {
                requireConfiguration(!alias.equals(CANONICAL_ROOT_COMMAND, ignoreCase = true), "commandAliases.$name", "must not use the reserved command name miers")
                requireConfiguration(configuredAliases.none { existing -> alias.equals(existing, ignoreCase = true) }, "commandAliases.$name", "duplicates another command alias")
                configuredAliases.add(alias)
            }
        }
    }

    companion object {
        const val MAX_CODE_POINTS: Int = 64
        private const val CANONICAL_ROOT_COMMAND = "miers"
    }
}

/** Strict YAML codec for the MieRS configuration document. */
object MiersConfigurationCodec {
    private fun newYaml(): Yaml = Yaml(
        SafeConstructor(
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 50
                codePointLimit = 1_000_000
            },
        ),
    )

    private val requiredKeys = setOf("blockedGroups", "maxQueue", "queueFullMessage", "cooldownMinutes")
    private val expectedKeys = requiredKeys + "commandAliases"
    private val expectedAliasKeys = setOf("query", "help", "toggle")

    @JvmStatic
    fun parse(content: String): MiersConfiguration {
        if (content.isBlank()) throw MiersConfigurationException("configuration must not be blank")

        val root = try {
            val documents = newYaml().loadAll(content).toList()
            requireConfiguration(documents.size == 1, "document", "must contain exactly one YAML document")
            documents.single()
        } catch (error: MiersConfigurationException) {
            throw error
        } catch (error: YAMLException) {
            throw MiersConfigurationException("invalid YAML configuration: ${error.message ?: "syntax error"}")
        } catch (error: RuntimeException) {
            throw MiersConfigurationException("invalid YAML configuration: ${error.message ?: "syntax error"}")
        }

        val values = root as? Map<*, *>
            ?: throw MiersConfigurationException("configuration root must be a YAML mapping")
        val stringKeys = values.keys.map { key ->
            key as? String ?: throw MiersConfigurationException("configuration keys must be strings")
        }
        val unknown = stringKeys.toSet() - expectedKeys
        if (unknown.isNotEmpty()) {
            throw MiersConfigurationException("unknown configuration field(s): ${unknown.sorted().joinToString(", ")}")
        }
        val missing = requiredKeys - stringKeys.toSet()
        if (missing.isNotEmpty()) {
            throw MiersConfigurationException("missing configuration field(s): ${missing.sorted().joinToString(", ")}")
        }

        val groupsValue = values["blockedGroups"]
        val groups = (groupsValue as? List<*>)
            ?: throw MiersConfigurationException("blockedGroups must be a YAML sequence")
        val parsedGroups = LinkedHashSet<String>(groups.size)
        groups.forEachIndexed { index, value ->
            val groupId = value as? String
                ?: throw MiersConfigurationException("blockedGroups[$index] must be a string")
            validateGroupId("blockedGroups[$index]", groupId)
            if (!parsedGroups.add(groupId)) {
                throw MiersConfigurationException("blockedGroups[$index] duplicates group ID $groupId")
            }
        }

        val maxQueue = values["maxQueue"] as? Int
            ?: throw MiersConfigurationException("maxQueue must be an integer")
        requireConfiguration(maxQueue > 0, "maxQueue", "must be a positive integer")

        val queueFullMessage = values["queueFullMessage"] as? String
            ?: throw MiersConfigurationException("queueFullMessage must be a string")

        val cooldownMinutes = values["cooldownMinutes"] as? Int
            ?: throw MiersConfigurationException("cooldownMinutes must be an integer")
        requireConfiguration(cooldownMinutes >= 0, "cooldownMinutes", "must be a non-negative integer")

        val commandAliases = if (values.containsKey("commandAliases")) {
            parseCommandAliases(values["commandAliases"])
        } else {
            MiersCommandAliases()
        }

        return try {
            MiersConfiguration(
                blockedGroups = immutableSortedSet(parsedGroups),
                maxQueue = maxQueue,
                queueFullMessage = queueFullMessage,
                cooldownMinutes = cooldownMinutes,
                commandAliases = commandAliases,
            )
        } catch (error: MiersConfigurationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw MiersConfigurationException(error.message ?: "invalid configuration")
        }
    }

    @JvmStatic
    fun render(configuration: MiersConfiguration): String {
        val value = configuration.immutableCopy()
        return buildString {
            appendLine("# 指令别名配置。插件原有的三条指令始终保留，别名只是额外入口。")
            appendLine("# 别名填写时不要带开头的 /，只能填写不含空白和 / 的单个名称；空字符串表示不启用别名。")
            appendLine("# 三个非空别名不能重复，也不能使用保留名称 miers。")
            appendLine("commandAliases:")
            appendLine("  # /miers：实时抓取 CodexRadar 数据，生成并发送模型 IQ 图片。")
            appendLine("  # 例如设置为 \"模型IQ\" 后，可使用 /模型IQ 触发查询。")
            appendLine("  query: ${yamlString(value.commandAliases.query)}")
            appendLine("  # /miers help：显示本插件全部指令及作用。")
            appendLine("  # 例如设置为 \"查询miers指令\" 后，可使用 /查询miers指令 查看帮助。")
            appendLine("  help: ${yamlString(value.commandAliases.help)}")
            appendLine("  # /miers st：仅供群管理员或群主切换本群是否禁用 IQ 查询。")
            appendLine("  # 例如设置为 \"切换miers状态\" 后，可使用 /切换miers状态 执行切换。")
            appendLine("  toggle: ${yamlString(value.commandAliases.toggle)}")
            appendLine()
            appendLine("# 禁用 /miers IQ 图片查询的群 ID 列表；不影响 /miers help 和 /miers st。")
            if (value.blockedGroups.isEmpty()) {
                appendLine("blockedGroups: []")
            } else {
                appendLine("blockedGroups:")
                value.blockedGroups.forEach { appendLine("  - ${yamlString(it)}") }
            }
            appendLine()
            appendLine("# 最大已接收 IQ 请求数，包含正在处理和等待中的任务，必须大于 0。")
            appendLine("maxQueue: ${value.maxQueue}")
            appendLine()
            appendLine("# 队列已满时发送的提示；设置为空字符串时不发送任何提示。")
            appendLine("queueFullMessage: ${yamlString(value.queueFullMessage)}")
            appendLine()
            appendLine("# 同一用户两次成功进入 IQ 请求队列的间隔分钟数；设置为 0 时不限制。")
            appendLine("cooldownMinutes: ${value.cooldownMinutes}")
        }
    }

    private fun parseCommandAliases(value: Any?): MiersCommandAliases {
        val aliases = value as? Map<*, *>
            ?: throw MiersConfigurationException("commandAliases must be a YAML mapping")
        val stringKeys = aliases.keys.map { key ->
            key as? String ?: throw MiersConfigurationException("commandAliases keys must be strings")
        }
        val unknown = stringKeys.toSet() - expectedAliasKeys
        if (unknown.isNotEmpty()) {
            throw MiersConfigurationException("unknown commandAliases field(s): ${unknown.sorted().joinToString(", ")}")
        }
        val missing = expectedAliasKeys - stringKeys.toSet()
        if (missing.isNotEmpty()) {
            throw MiersConfigurationException("missing commandAliases field(s): ${missing.sorted().joinToString(", ")}")
        }

        fun stringValue(name: String): String = aliases[name] as? String
            ?: throw MiersConfigurationException("commandAliases.$name must be a string")

        return MiersCommandAliases(
            query = stringValue("query"),
            help = stringValue("help"),
            toggle = stringValue("toggle"),
        )
    }
}

/**
 * Thread-safe configuration store. A group toggle is persisted before the in-memory
 * snapshot is changed, so a failed write never reports a state that was not saved.
 */
class MiersConfigurationStore private constructor(
    configurationFile: Path,
    initialConfiguration: MiersConfiguration,
) {
    val configurationFile: Path = configurationFile.toAbsolutePath().normalize()

    private val writeLock = ReentrantLock()
    private val current = AtomicReference(initialConfiguration.immutableCopy())

    fun snapshot(): MiersConfiguration = current.get()

    fun current(): MiersConfiguration = snapshot()

    fun isGroupBlocked(groupId: String): Boolean = snapshot().isGroupBlocked(groupId)

    fun isBlocked(groupId: String): Boolean = isGroupBlocked(groupId)

    /**
     * Toggles a group's deny-list entry and returns the resulting state: true when
     * the group is now blocked, false when it is now enabled.
     */
    fun toggleGroup(groupId: String): Boolean = writeLock.withLock {
        validateGroupId("groupId", groupId)
        val before = current.get()
        val nextBlocked = before.blockedGroups.toMutableSet()
        val nowBlocked = if (nextBlocked.add(groupId)) true else {
            nextBlocked.remove(groupId)
            false
        }
        val next = before.copy(blockedGroups = nextBlocked).immutableCopy()
        writeAtomically(configurationFile, MiersConfigurationCodec.render(next))
        current.set(next)
        nowBlocked
    }

    /** Explicit toggle variant for handlers that already know the target state. */
    fun setGroupBlocked(groupId: String, blocked: Boolean): MiersConfiguration = writeLock.withLock {
        validateGroupId("groupId", groupId)
        val before = current.get()
        val nextBlocked = before.blockedGroups.toMutableSet()
        if (blocked) nextBlocked.add(groupId) else nextBlocked.remove(groupId)
        val next = before.copy(blockedGroups = nextBlocked).immutableCopy()
        if (next != before) {
            writeAtomically(configurationFile, MiersConfigurationCodec.render(next))
            current.set(next)
        }
        next
    }

    fun replace(configuration: MiersConfiguration): MiersConfiguration = update { configuration }

    fun update(transform: (MiersConfiguration) -> MiersConfiguration): MiersConfiguration = writeLock.withLock {
        val next = transform(current.get()).immutableCopy()
        writeAtomically(configurationFile, MiersConfigurationCodec.render(next))
        current.set(next)
        next
    }

    companion object {
        @JvmStatic
        fun open(configurationFile: Path, configurationContent: String): MiersConfigurationStore =
            MiersConfigurationStore(configurationFile, MiersConfigurationCodec.parse(configurationContent))

        @JvmStatic
        fun load(configurationFile: Path): MiersConfigurationStore {
            val normalized = configurationFile.toAbsolutePath().normalize()
            return open(normalized, Files.readString(normalized, StandardCharsets.UTF_8))
        }

        private fun writeAtomically(target: Path, content: String) {
            val parent = target.parent ?: throw IOException("configuration file must have a parent directory")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
            try {
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}

class MiersConfigurationException(message: String) : IllegalArgumentException(message)

private fun validateGroupIds(groupIds: Set<String>) {
    groupIds.forEachIndexed { index, groupId -> validateGroupId("blockedGroups[$index]", groupId) }
}

private fun validateGroupId(field: String, value: String) {
    requireConfiguration(value.isNotBlank(), field, "must not be blank")
    requireConfiguration(value == value.trim(), field, "must not have leading or trailing whitespace")
    requireConfiguration(value.codePointCount(0, value.length) <= 255, field, "must not exceed 255 Unicode code points")
    requireConfiguration(value.codePoints().noneMatch(Character::isWhitespace), field, "must not contain whitespace")
    requireConfiguration(value.codePoints().noneMatch(Character::isISOControl), field, "must not contain control characters")
    requireConfiguration(!hasUnpairedSurrogate(value), field, "contains an invalid Unicode character")
}

private fun validateCommandAlias(field: String, value: String) {
    if (value.isEmpty()) return
    requireConfiguration(value.isNotBlank(), field, "must not be blank")
    requireConfiguration(value == value.trim(), field, "must not have leading or trailing whitespace")
    requireConfiguration(value.codePointCount(0, value.length) <= MiersCommandAliases.MAX_CODE_POINTS, field, "must not exceed ${MiersCommandAliases.MAX_CODE_POINTS} Unicode code points")
    requireConfiguration(value.codePoints().noneMatch(Character::isWhitespace), field, "must not contain whitespace")
    requireConfiguration('/' !in value, field, "must not contain /")
    requireConfiguration(value.codePoints().noneMatch(Character::isISOControl), field, "must not contain control characters")
    requireConfiguration(!hasUnpairedSurrogate(value), field, "contains an invalid Unicode character")
}

private fun requireConfiguration(condition: Boolean, field: String, detail: String) {
    if (!condition) throw MiersConfigurationException("$field: $detail")
}

private fun unsupportedControl(codePoint: Int): Boolean =
    Character.isISOControl(codePoint) && codePoint != '\n'.code && codePoint != '\r'.code && codePoint != '\t'.code

private fun hasUnpairedSurrogate(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                index += 2
            }
            Character.isLowSurrogate(current) -> return true
            else -> index++
        }
    }
    return false
}

private fun immutableSortedSet(source: Collection<String>): Set<String> =
    Collections.unmodifiableSortedSet(TreeSet(source))

private fun yamlString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (Character.isISOControl(character)) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
