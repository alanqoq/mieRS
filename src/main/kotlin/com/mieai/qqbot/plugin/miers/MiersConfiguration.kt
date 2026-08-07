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

/** Strict YAML codec for the four-field MieRS configuration document. */
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

    private val expectedKeys = setOf("blockedGroups", "maxQueue", "queueFullMessage", "cooldownMinutes")

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
        val missing = expectedKeys - stringKeys.toSet()
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

        return try {
            MiersConfiguration(
                blockedGroups = immutableSortedSet(parsedGroups),
                maxQueue = maxQueue,
                queueFullMessage = queueFullMessage,
                cooldownMinutes = cooldownMinutes,
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
