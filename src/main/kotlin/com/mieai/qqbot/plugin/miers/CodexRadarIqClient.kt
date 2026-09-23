package com.mieai.qqbot.plugin.miers

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

private fun comboKey(model: String, effort: String): String = "$model|$effort"

/** Loads and validates the available preferred IQ tiers from CodexRadar. */
class CodexRadarIqClient(
    private val httpClient: PluginHttpClient,
) {
    /** Starts one uncached request and completes without blocking the caller. */
    fun fetch(): CompletionStage<List<MiersIqModel>> {
        val request = PluginHttpRequest(
            method = "GET",
            uri = URI.create(ENDPOINT),
            headers = mapOf(
                "Accept" to "application/json",
                "Cache-Control" to "no-cache",
            ),
            timeout = REQUEST_TIMEOUT,
        )

        val responseStage = try {
            httpClient.send(request).also { requireNotNull(it) { "CodexRadar HTTP client returned no stage" } }
        } catch (failure: Exception) {
            return CompletableFuture.failedFuture(
                CodexRadarIqException("CodexRadar IQ request could not be started", failure),
            )
        }

        return responseStage
            .handle { response, failure ->
                if (failure != null) {
                    val cause = unwrapCompletionFailure(failure)
                    if (cause is CodexRadarIqException) throw cause
                    throw CodexRadarIqException("CodexRadar IQ request failed", cause)
                }
                response ?: throw CodexRadarIqException("CodexRadar IQ request returned no response")
            }
            .thenApply(::parseResponse)
    }

    private fun parseResponse(response: PluginHttpResponse): List<MiersIqModel> {
        if (response.statusCode != HTTP_OK) {
            throw CodexRadarIqException("CodexRadar IQ request returned HTTP ${response.statusCode}")
        }
        if (!hasJsonContentType(response.headers)) {
            throw CodexRadarIqException("CodexRadar IQ response content type is not application/json")
        }

        val body = response.body
        return try {
            parseBody(body)
        } catch (failure: CodexRadarIqException) {
            throw failure
        } catch (_: Exception) {
            // Do not retain or expose parser messages that might contain response text.
            throw CodexRadarIqException("CodexRadar IQ response JSON is invalid")
        }
    }

    private fun parseBody(body: ByteArray): List<MiersIqModel> {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return InputStreamReader(ByteArrayInputStream(body), decoder).use { input ->
            JsonReader(input).use { reader ->
                reader.strictness = Strictness.STRICT
                val payload = readPayload(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    invalid("JSON contains trailing data")
                }
                buildModels(payload.combos, payload.taskIds, payload.cells)
            }
        }
    }

    private fun readPayload(reader: JsonReader): ParsedPayload {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid("top-level JSON value must be an object")
        reader.beginObject()

        val fields = HashSet<String>()
        var schema: Int? = null
        var combos: Set<String>? = null
        var taskIds: List<String>? = null
        var cells: Map<String, Boolean?>? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) invalid("top-level JSON contains duplicate fields")
            when (name) {
                "schema" -> {
                    if (reader.peek() != JsonToken.NUMBER) invalid("schema must be the number 1")
                    schema = reader.nextInt()
                }

                "combos" -> combos = readCombos(reader)
                "tasks" -> taskIds = readTasks(reader)
                "cells" -> cells = readCells(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (schema != SCHEMA_VERSION) invalid("schema must be 1")
        val verifiedCombos = combos ?: invalid("combos is required")
        if (verifiedCombos.isEmpty()) invalid("combos must not be empty")
        val verifiedTasks = taskIds ?: invalid("tasks is required")
        if (verifiedTasks.isEmpty()) invalid("tasks must not be empty")
        val verifiedCells = cells ?: invalid("cells is required")

        val taskSet = verifiedTasks.toHashSet()
        verifiedCells.keys.forEach { key ->
            val taskId = parseCellKey(key).first
            if (taskId !in taskSet) invalid("cells contain an unknown task")
        }
        return ParsedPayload(verifiedCombos, verifiedTasks, verifiedCells)
    }

    private fun readCombos(reader: JsonReader): Set<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) invalid("combos must be an array")
        val seen = LinkedHashSet<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            val combo = readCombo(reader)
            val key = comboKey(combo.model, combo.effort)
            if (!seen.add(key)) invalid("combos contain a duplicate combination")
        }
        reader.endArray()
        return seen
    }

    private fun readCombo(reader: JsonReader): ComboFields {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid("each combo must be an object")
        reader.beginObject()
        val fields = HashSet<String>()
        var model: String? = null
        var effort: String? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) invalid("combo objects contain duplicate fields")
            when (name) {
                "model" -> model = readString(reader, "combo model")
                "effort" -> effort = readString(reader, "combo effort")
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ComboFields(
            model ?: invalid("combo model is missing"),
            effort ?: invalid("combo effort is missing"),
        )
    }

    private fun readTasks(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) invalid("tasks must be an array")
        val seen = HashSet<String>()
        val taskIds = ArrayList<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid("each task must be an object")
            reader.beginObject()
            val fields = HashSet<String>()
            var id: String? = null
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!fields.add(name)) invalid("task objects contain duplicate fields")
                when (name) {
                    "id" -> id = readString(reader, "task id")
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            val taskId = id ?: invalid("task id is missing")
            if (taskId.isBlank() || '|' in taskId) invalid("task id is invalid")
            if (!seen.add(taskId)) invalid("tasks contain duplicate IDs")
            taskIds += taskId
        }
        reader.endArray()
        return taskIds
    }

    private fun readCells(reader: JsonReader): Map<String, Boolean?> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid("cells must be an object")
        val seen = HashSet<String>()
        val cells = HashMap<String, Boolean?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (!seen.add(key)) invalid("cells contain a duplicate key")
            val combo = parseCellKey(key).second
            if (combo in EXPECTED_COMBO_BY_KEY) {
                cells[key] = readCell(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return cells
    }

    private fun readCell(reader: JsonReader): Boolean? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid("each cell must be an object")
        reader.beginObject()
        val fields = HashSet<String>()
        var firstPassed: Boolean? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) invalid("cell objects contain duplicate fields")
            when (name) {
                "ran_by" -> firstPassed = readRanBy(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return firstPassed
    }

    private fun readRanBy(reader: JsonReader): Boolean? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) invalid("ran_by must be an array")
        reader.beginArray()
        var index = 0
        var firstPassed: Boolean? = null
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid("ran_by entries must be objects")
            val passed = readRun(reader)
            if (index == 0) firstPassed = passed
            index++
        }
        reader.endArray()
        return firstPassed
    }

    private fun readRun(reader: JsonReader): Boolean? {
        reader.beginObject()
        val fields = HashSet<String>()
        var passed: Boolean? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) invalid("ran_by entries contain duplicate fields")
            if (name == "passed") {
                passed = if (reader.peek() == JsonToken.BOOLEAN) reader.nextBoolean() else {
                    reader.skipValue()
                    null
                }
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return passed
    }

    private fun buildModels(
        combos: Set<String>,
        taskIds: List<String>,
        cells: Map<String, Boolean?>,
    ): List<MiersIqModel> {
        val models = EXPECTED_COMBOS.mapNotNull { combo ->
            val key = comboKey(combo.model, combo.effort)
            if (key !in combos) return@mapNotNull null

            var validTasks = 0
            var passedTasks = 0
            taskIds.forEach { taskId ->
                val passed = cells["$taskId|${combo.model}|${combo.effort}"] ?: return@forEach
                validTasks++
                if (passed) passedTasks++
            }
            if (validTasks == 0) return@mapNotNull null

            val iq = passedTasks.toDouble() / validTasks.toDouble() * IQ_MULTIPLIER
            if (!iq.isFinite() || iq !in IQ_MIN..IQ_MAX) invalid("computed IQ is outside the supported range")
            MiersIqModel(
                name = combo.name,
                iq = iq,
                family = combo.family,
                strength = combo.effort,
                passedTasks = passedTasks,
                totalTasks = validTasks,
            )
        }
        if (models.isEmpty()) invalid("no supported combinations have valid samples")
        return models
    }

    private fun readString(reader: JsonReader, field: String): String {
        if (reader.peek() != JsonToken.STRING) invalid("$field must be a string")
        return reader.nextString()
    }

    private fun parseCellKey(key: String): Pair<String, String> {
        val firstSeparator = key.indexOf('|')
        val secondSeparator = key.indexOf('|', firstSeparator + 1)
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1 || secondSeparator >= key.lastIndex) {
            invalid("cell key has an invalid shape")
        }
        if (key.indexOf('|', secondSeparator + 1) >= 0) invalid("cell key has an invalid shape")
        val taskId = key.substring(0, firstSeparator)
        val combo = key.substring(firstSeparator + 1)
        return taskId to combo
    }

    private fun hasJsonContentType(headers: Map<String, List<String>>): Boolean {
        val values = headers.entries
            .filter { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            .flatMap { (_, values) -> values }
        return values.isNotEmpty() && values.all { value ->
            value.substringBefore(';').trim().equals("application/json", ignoreCase = true)
        }
    }

    private fun unwrapCompletionFailure(failure: Throwable): Throwable {
        var cause = failure
        while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) {
            cause = cause.cause!!
        }
        return cause
    }

    private fun invalid(detail: String): Nothing =
        throw CodexRadarIqException("CodexRadar IQ response is invalid: $detail")

    private data class ParsedPayload(
        val combos: Set<String>,
        val taskIds: List<String>,
        val cells: Map<String, Boolean?>,
    )

    private data class ComboFields(
        val model: String,
        val effort: String,
    )

    private data class ExpectedCombo(
        val model: String,
        val effort: String,
        val name: String,
        val family: MiersModelFamily,
    )

    companion object {
        const val ENDPOINT: String = "https://codexradar.com/api/intelligence-efficiency?refresh=1"

        private const val HTTP_OK = 200
        private const val SCHEMA_VERSION = 1
        private const val IQ_MULTIPLIER = 150.0
        private const val IQ_MIN = 0.0
        private const val IQ_MAX = 120.0
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)

        private val STRENGTHS = listOf("ultra", "max", "xhigh", "high", "medium", "low")
        private val EXPECTED_COMBOS: List<ExpectedCombo> = buildList {
            fun model(id: String, name: String, family: MiersModelFamily, efforts: List<String> = STRENGTHS) {
                addAll(efforts.map { effort -> ExpectedCombo(id, effort, name, family) })
            }
            model("gpt-5.6-sol", "GPT5.6 Sol", MiersModelFamily.SOL)
            model("gpt-5.6-terra", "GPT5.6 Terra", MiersModelFamily.TERRA)
            model("gpt-5.6-luna", "GPT5.6 Luna", MiersModelFamily.LUNA, STRENGTHS.drop(1))
            model("gpt-6-astra", "GPT-6 Astra", MiersModelFamily.ASTRA)
            model("gpt-6-sol", "GPT-6 Sol", MiersModelFamily.SOL)
            model("gpt-6-luna", "GPT-6 Luna", MiersModelFamily.LUNA, STRENGTHS.drop(1))
            model("gpt-5.5", "GPT5.5", MiersModelFamily.GPT55, listOf("xhigh", "high"))
            model("deepseek-v4-flash", "DeepSeek V4 Flash", MiersModelFamily.DEEPSEEK, listOf("max", "high"))
            model("deepseek-v4-pro", "DeepSeek V4 Pro", MiersModelFamily.DEEPSEEK, listOf("max", "high"))
            model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", MiersModelFamily.DEEPSEEK, listOf("max", "high"))
            model("dsh-deepseek-v4-flash", "DeepSeek V4 Flash DSH", MiersModelFamily.DEEPSEEK, listOf("max", "high"))
            model("dsh-deepseek-v4.1-flash", "DeepSeek V4.1 Flash DSH", MiersModelFamily.DEEPSEEK, listOf("max", "high"))
            model("dsh-deepseek-v4-flash-vision-exp", "DeepSeek V4 Flash Vision DSH", MiersModelFamily.DEEPSEEK, listOf("max", "high"))
        }
        private val EXPECTED_COMBO_BY_KEY: Map<String, ExpectedCombo> =
            EXPECTED_COMBOS.associateBy { comboKey(it.model, it.effort) }
    }
}

/** Failure raised when CodexRadar cannot provide a trusted IQ table. */
class CodexRadarIqException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
