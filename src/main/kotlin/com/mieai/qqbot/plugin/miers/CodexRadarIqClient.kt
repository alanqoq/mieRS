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

/** Loads and validates CodexRadar's public per-configuration RadarBench scores. */
class CodexRadarIqClient(
    private val httpClient: PluginHttpClient,
) {
    /** Starts uncached reads and completes without blocking the caller. */
    fun fetch(): CompletionStage<List<MiersIqModel>> =
        fetchBatches(EXPECTED_COMBOS.chunked(MAX_CONCURRENCY), emptyList()).thenApply { results ->
            val models = results.mapNotNull(ComboReadResult::model)
            if (models.isEmpty()) {
                throw results.firstNotNullOfOrNull(ComboReadResult::failure)
                    ?: CodexRadarIqException("CodexRadar returned no scored configurations")
            }
            models
        }

    private fun fetchBatches(
        batches: List<List<ExpectedCombo>>,
        completed: List<ComboReadResult>,
    ): CompletionStage<List<ComboReadResult>> {
        if (batches.isEmpty()) return CompletableFuture.completedFuture(completed)
        val current = batches.first().map(::fetchCombo).map(CompletionStage<ComboReadResult>::toCompletableFuture)
        return CompletableFuture.allOf(*current.toTypedArray()).thenCompose {
            fetchBatches(batches.drop(1), completed + current.map(CompletableFuture<ComboReadResult>::join))
        }
    }

    private fun fetchCombo(combo: ExpectedCombo): CompletionStage<ComboReadResult> {
        val request = PluginHttpRequest(
            method = "GET",
            uri = URI.create("$ENDPOINT?model=${combo.model}&effort=${combo.effort}&view=summary"),
            headers = mapOf(
                "Accept" to "application/json",
                "Cache-Control" to "no-cache",
                "X-Radar-Bench-Selection" to "user-selected-v1",
            ),
            timeout = REQUEST_TIMEOUT,
        )

        val responseStage = try {
            httpClient.send(request).also { requireNotNull(it) { "CodexRadar HTTP client returned no stage" } }
        } catch (failure: Exception) {
            return CompletableFuture.completedFuture(
                ComboReadResult(null, CodexRadarIqException("CodexRadar IQ request could not be started", failure)),
            )
        }

        return responseStage.handle { response, failure ->
            when {
                failure != null -> {
                    val cause = unwrapCompletionFailure(failure)
                    val requestFailure = cause as? CodexRadarIqException
                        ?: CodexRadarIqException("CodexRadar IQ request failed for ${combo.model}/${combo.effort}", cause)
                    ComboReadResult(null, requestFailure)
                }

                response == null -> ComboReadResult(
                    null,
                    CodexRadarIqException("CodexRadar IQ request returned no response for ${combo.model}/${combo.effort}"),
                )

                else -> try {
                    ComboReadResult(parseResponse(response, combo), null)
                } catch (requestFailure: CodexRadarIqException) {
                    ComboReadResult(null, requestFailure)
                } catch (_: Exception) {
                    ComboReadResult(null, CodexRadarIqException("CodexRadar IQ response JSON is invalid"))
                }
            }
        }
    }

    private fun parseResponse(response: PluginHttpResponse, expected: ExpectedCombo): MiersIqModel? {
        if (response.statusCode != HTTP_OK) {
            throw CodexRadarIqException("CodexRadar IQ request returned HTTP ${response.statusCode}")
        }
        if (!hasJsonContentType(response.headers)) {
            throw CodexRadarIqException("CodexRadar IQ response content type is not application/json")
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            InputStreamReader(ByteArrayInputStream(response.body), decoder).use { input ->
                JsonReader(input).use { reader ->
                    reader.strictness = Strictness.STRICT
                    val model = readSummary(reader, expected)
                    if (reader.peek() != JsonToken.END_DOCUMENT) invalid("JSON contains trailing data")
                    model
                }
            }
        } catch (failure: CodexRadarIqException) {
            throw failure
        } catch (_: Exception) {
            throw CodexRadarIqException("CodexRadar IQ response JSON is invalid")
        }
    }

    private fun readSummary(reader: JsonReader, expected: ExpectedCombo): MiersIqModel? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid("top-level JSON value must be an object")
        reader.beginObject()

        val fields = HashSet<String>()
        var schema: Int? = null
        var scoreVersion: String? = null
        var scoreMax: Double? = null
        var model: String? = null
        var effort: String? = null
        var score: Double? = null
        var scoreFieldSeen = false
        var coverage: Int? = null
        var requiredTasks: Int? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) invalid("summary contains duplicate fields")
            when (name) {
                "schema" -> {
                    if (reader.peek() != JsonToken.NUMBER) invalid("schema must be the number 1")
                    schema = reader.nextInt()
                }

                "score_version" -> scoreVersion = readString(reader, "score_version")
                "score_max" -> scoreMax = readFiniteDouble(reader, "score_max")
                "model" -> model = readString(reader, "model")
                "effort" -> effort = readString(reader, "effort")
                "score" -> {
                    scoreFieldSeen = true
                    score = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        readFiniteDouble(reader, "score")
                    }
                }

                "coverage" -> coverage = readNonNegativeInt(reader, "coverage")
                "required_tasks" -> requiredTasks = readNonNegativeInt(reader, "required_tasks")
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (schema != SCHEMA_VERSION) invalid("schema must be 1")
        if (scoreVersion != SCORE_VERSION) invalid("score_version is unsupported")
        if (scoreMax != IQ_MAX) invalid("score_max is unsupported")
        if (model != expected.model || effort != expected.effort) invalid("score selection does not match the request")
        if (!scoreFieldSeen) invalid("score is missing")

        val verifiedCoverage = coverage ?: invalid("coverage is missing")
        val verifiedRequiredTasks = requiredTasks ?: invalid("required_tasks is missing")
        if (verifiedRequiredTasks == 0 || verifiedCoverage > verifiedRequiredTasks) {
            invalid("coverage is outside the required task range")
        }
        if (score == null) {
            if (verifiedCoverage != 0) invalid("score is missing despite available coverage")
            return null
        }
        if (verifiedCoverage == 0 || score !in IQ_MIN..IQ_MAX) invalid("score is outside the supported range")

        return MiersIqModel(
            name = expected.name,
            iq = score,
            family = expected.family,
            strength = expected.effort,
            coveredTasks = verifiedCoverage,
            requiredTasks = verifiedRequiredTasks,
        )
    }

    private fun readString(reader: JsonReader, field: String): String {
        if (reader.peek() != JsonToken.STRING) invalid("$field must be a string")
        return reader.nextString()
    }

    private fun readNonNegativeInt(reader: JsonReader, field: String): Int {
        if (reader.peek() != JsonToken.NUMBER) invalid("$field must be a non-negative integer")
        val value = try {
            reader.nextInt()
        } catch (_: Exception) {
            invalid("$field must be a non-negative integer")
        }
        if (value < 0) invalid("$field must be a non-negative integer")
        return value
    }

    private fun readFiniteDouble(reader: JsonReader, field: String): Double {
        if (reader.peek() != JsonToken.NUMBER) invalid("$field must be a finite number")
        val value = try {
            reader.nextDouble()
        } catch (_: Exception) {
            invalid("$field must be a finite number")
        }
        if (!value.isFinite()) invalid("$field must be a finite number")
        return value
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

    private data class ComboReadResult(
        val model: MiersIqModel?,
        val failure: CodexRadarIqException?,
    )

    private data class ExpectedCombo(
        val model: String,
        val effort: String,
        val name: String,
        val family: MiersModelFamily,
    )

    companion object {
        const val ENDPOINT: String = "https://api.codexradar.com/api/v1/radar-bench-score"

        private const val HTTP_OK = 200
        private const val SCHEMA_VERSION = 1
        private const val SCORE_VERSION = "radar-bench-v2-150"
        private const val IQ_MIN = 0.0
        private const val IQ_MAX = 150.0
        private const val MAX_CONCURRENCY = 3
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)

        private val STRENGTHS = listOf("ultra", "max", "xhigh", "high", "medium", "low")
        private val EXPECTED_COMBOS: List<ExpectedCombo> = buildList {
            fun model(id: String, name: String, family: MiersModelFamily, efforts: List<String> = STRENGTHS) {
                addAll(efforts.map { effort -> ExpectedCombo(id, effort, name, family) })
            }

            model("gpt-6-astra", "GPT-6 Astra", MiersModelFamily.ASTRA)
            model("gpt-6.1-sol", "GPT-6.1 Sol", MiersModelFamily.SOL)
            model("gpt-6-sol", "GPT-6 Sol", MiersModelFamily.SOL)
            model("gpt-5.6-sol", "GPT-5.6 Sol", MiersModelFamily.SOL)
            model("gpt-6-luna", "GPT-6 Luna", MiersModelFamily.LUNA, STRENGTHS.drop(1))
            model("gpt-5.6-luna", "GPT-5.6 Luna", MiersModelFamily.LUNA, STRENGTHS.drop(1))
        }
    }
}

/** Failure raised when CodexRadar cannot provide a trusted IQ table. */
class CodexRadarIqException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
