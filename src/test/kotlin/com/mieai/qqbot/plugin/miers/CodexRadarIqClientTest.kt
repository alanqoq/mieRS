package com.mieai.qqbot.plugin.miers

import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexRadarIqClientTest {
    @Test
    fun `fetch calculates all 29 live IQ values and sends a fresh request every time`() {
        val http = RecordingHttpClient { jsonResponse(validPayload()) }
        val client = CodexRadarIqClient(http)

        val first = client.fetch().toCompletableFuture().join()
        val second = client.fetch().toCompletableFuture().join()

        assertEquals(29, first.size)
        assertEquals(first, second)
        assertEquals("GPT5.6 Sol", first.first().name)
        assertEquals("ultra", first.first().strength)
        assertEquals(75.0, first.first().iq)
        assertEquals(5, first.first().passedTasks)
        assertEquals(10, first.first().totalTasks)
        assertEquals("GPT5.6 Luna", first[12].name)
        assertEquals("max", first[12].strength)
        val astra = first.filter { it.name == "GPT-6 Astra" }
        assertEquals(6, astra.size)
        assertEquals(listOf("ultra", "max", "xhigh", "high", "medium", "low"), astra.map { it.strength })
        assertTrue(astra.all { it.family == MiersModelFamily.ASTRA })
        assertEquals("GPT-6 Astra", astra.first().name)
        assertEquals("GPT5.5", first[23].name)
        assertEquals("xhigh", first[23].strength)
        assertEquals("DeepSeek V4 Flash", first[25].name)
        assertEquals("max", first[25].strength)
        assertEquals(15.0, first[25].iq)
        assertEquals("DeepSeek V4 Pro", first[27].name)
        assertEquals(MiersModelFamily.DEEPSEEK, first[27].family)
        assertEquals("max", first[27].strength)
        assertEquals("DeepSeek V4 Pro", first[28].name)
        assertEquals(MiersModelFamily.DEEPSEEK, first[28].family)
        assertEquals("high", first[28].strength)

        val requests = http.requests()
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("GET", request.method)
            assertEquals(CodexRadarIqClient.ENDPOINT, request.uri.toString())
            assertEquals("application/json", request.headers["Accept"])
            assertEquals("no-cache", request.headers["Cache-Control"])
            assertEquals(20, request.timeout.seconds)
        }
    }

    @Test
    fun `fetch rejects bad HTTP status and response content type`() {
        val statusClient = CodexRadarIqClient(
            RecordingHttpClient { PluginHttpResponse(503, jsonHeaders(), "{}".toByteArray()) },
        )
        assertRejected(statusClient, "HTTP 503")

        val contentTypeClient = CodexRadarIqClient(
            RecordingHttpClient {
                PluginHttpResponse(200, mapOf("Content-Type" to listOf("text/html")), validPayload().toByteArray())
            },
        )
        assertRejected(contentTypeClient, "content type")
    }

    @Test
    fun `fetch accepts large and rejects malformed or trailing JSON responses`() {
        val large = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(validPayload() + " ".repeat(8 * 1024 * 1024 + 1)) },
        )
        assertEquals(29, large.fetch().toCompletableFuture().join().size)

        val malformed = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse("{not JSON") },
        )
        assertRejected(malformed, "JSON is invalid")

        val trailing = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(validPayload() + " true") },
        )
        assertRejected(trailing)
    }

    @Test
    fun `fetch rejects duplicate fields and tables without supported combinations`() {
        val duplicate = validPayload().replaceFirst("{", "{\"schema\":1,")
        assertRejected(CodexRadarIqClient(RecordingHttpClient { jsonResponse(duplicate) }), "duplicate fields")

        val unsupported = validPayload(combos = listOf(Combo("unknown-model", "low")))
        assertRejected(
            CodexRadarIqClient(RecordingHttpClient { jsonResponse(unsupported) }),
            "no supported combinations",
        )
    }

    @Test
    fun `fetch tolerates supported combinations removed upstream`() {
        val models = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(validPayload(combos = SITE_COMBOS)) },
        ).fetch().toCompletableFuture().join()

        assertEquals(21, models.size)
        assertTrue(models.none { it.name == "DeepSeek V4 Pro" })
    }

    @Test
    fun `fetch recognizes current GPT and DeepSeek combinations and omits missing or invalid tiers`() {
        val current = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(validPayload(combos = CURRENT_COMBOS)) },
        ).fetch().toCompletableFuture().join()
        assertEquals(46, current.size)
        assertTrue(current.any { it.name == "GPT-6 Sol" })
        assertTrue(current.any { it.name == "GPT-6 Luna" && it.strength == "low" })
        assertTrue(current.any { it.name == "DeepSeek V4.1 Flash" })
        assertTrue(current.any { it.name == "DeepSeek V4 Flash DSH" })
        assertTrue(current.any { it.name == "DeepSeek V4 Flash Vision DSH" })

        val missing = CURRENT_COMBOS.filterNot { it == Combo("gpt-6-astra", "ultra") }
        val missingModels = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(validPayload(combos = missing)) },
        ).fetch().toCompletableFuture().join()
        assertEquals(45, missingModels.size)
        assertTrue(missingModels.none { it.name == "GPT-6 Astra" && it.strength == "ultra" })

        val partial = validPayload(combos = CURRENT_COMBOS, noValidSamplesFor = "gpt-6-astra|ultra")
        val partialModels = CodexRadarIqClient(RecordingHttpClient { jsonResponse(partial) }).fetch().toCompletableFuture().join()
        assertEquals(45, partialModels.size)
        assertTrue(partialModels.any { it.name == "GPT-6 Astra" && it.strength == "max" })
    }

    @Test
    fun `fetch accepts additional combinations published by CodexRadar`() {
        val extended = SITE_COMBOS + PRO_COMBOS + listOf(
            Combo("deepseek-v4-flash", "low"),
            Combo("deepseek-v4-pro", "low"),
        )

        val models = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(validPayload(combos = extended)) },
        ).fetch().toCompletableFuture().join()

        assertEquals(23, models.size)
    }

    @Test
    fun `fetch ignores cell payloads for additional combinations`() {
        val extra = Combo("deepseek-v4-pro", "low")
        val original = validPayload(combos = SITE_COMBOS + PRO_COMBOS + extra)
        val extraCell =
            "\"task-1|${extra.model}|${extra.effort}\":{\"ran_by\":[{\"passed\":true},{\"passed\":true}]}"
        assertTrue(original.contains(extraCell))
        val payload = original.replaceFirst(
            extraCell,
            "\"task-1|${extra.model}|${extra.effort}\":true",
        )

        val models = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(payload) },
        ).fetch().toCompletableFuture().join()

        assertEquals(23, models.size)
    }

    @Test
    fun `fetch rejects only when no supported combinations have valid samples`() {
        val partial = validPayload(noValidSamplesFor = "gpt-5.6-sol|low")
        val retained = CodexRadarIqClient(RecordingHttpClient { jsonResponse(partial) }).fetch().toCompletableFuture().join()
        assertEquals(28, retained.size)
        assertTrue(retained.none { it.name == "GPT5.6 Sol" && it.strength == "low" })

        assertRejected(
            CodexRadarIqClient(RecordingHttpClient { jsonResponse(validPayload(noValidSamplesFor = "*")) }),
            "no supported combinations",
        )
    }

    private fun assertRejected(client: CodexRadarIqClient, expectedMessage: String? = null) {
        val failure = assertFailsWith<CompletionException> {
            client.fetch().toCompletableFuture().join()
        }
        val cause = assertIs<CodexRadarIqException>(failure.cause)
        if (expectedMessage != null) assertTrue(cause.message.orEmpty().contains(expectedMessage))
    }

    private fun validPayload(
        combos: List<Combo> = SITE_COMBOS + ASTRA_COMBOS + PRO_COMBOS,
        noValidSamplesFor: String? = null,
    ): String {
        val taskIds = (1..TASK_COUNT).map { "task-$it" }
        val comboJson = combos.joinToString(",") { combo ->
            "{\"model\":\"${combo.model}\",\"effort\":\"${combo.effort}\"}"
        }
        val taskJson = taskIds.joinToString(",") { id -> "{\"id\":\"$id\"}" }
        val cellsJson = buildList {
            taskIds.forEach { taskId ->
                combos.forEachIndexed { comboIndex, combo ->
                    val key = "${combo.model}|${combo.effort}"
                    val firstRun = when {
                        noValidSamplesFor == "*" || key == noValidSamplesFor -> "{}"
                        taskId.removePrefix("task-").toInt() <= comboIndex % 9 -> "{\"passed\":true}"
                        else -> "{\"passed\":false}"
                    }
                    add("\"$taskId|$key\":{\"ran_by\":[$firstRun,{\"passed\":true}]}")
                }
            }
        }.joinToString(",")
        return "{\"schema\":1,\"combos\":[$comboJson],\"tasks\":[$taskJson],\"cells\":{$cellsJson}}"
    }

    private fun jsonResponse(content: String): PluginHttpResponse =
        PluginHttpResponse(200, jsonHeaders(), content.toByteArray(StandardCharsets.UTF_8))

    private fun jsonHeaders(): Map<String, List<String>> =
        mapOf("content-type" to listOf("application/json; charset=utf-8"))

    private class RecordingHttpClient(
        private val responder: (PluginHttpRequest) -> PluginHttpResponse,
    ) : PluginHttpClient {
        private val requests = mutableListOf<PluginHttpRequest>()

        override fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse> {
            synchronized(requests) {
                requests += request
            }
            return try {
                CompletableFuture.completedFuture(responder(request))
            } catch (failure: RuntimeException) {
                CompletableFuture.failedFuture(failure)
            }
        }

        fun requests(): List<PluginHttpRequest> = synchronized(requests) { requests.toList() }
    }

    private data class Combo(
        val model: String,
        val effort: String,
    )

    private companion object {
        const val TASK_COUNT = 10

        val SITE_COMBOS = listOf(
            Combo("gpt-5.6-sol", "low"),
            Combo("gpt-5.6-sol", "medium"),
            Combo("gpt-5.6-sol", "high"),
            Combo("gpt-5.6-sol", "xhigh"),
            Combo("gpt-5.6-sol", "max"),
            Combo("gpt-5.6-sol", "ultra"),
            Combo("gpt-5.6-terra", "low"),
            Combo("gpt-5.6-terra", "medium"),
            Combo("gpt-5.6-terra", "high"),
            Combo("gpt-5.6-terra", "xhigh"),
            Combo("gpt-5.6-terra", "max"),
            Combo("gpt-5.6-terra", "ultra"),
            Combo("gpt-5.6-luna", "low"),
            Combo("gpt-5.6-luna", "medium"),
            Combo("gpt-5.6-luna", "high"),
            Combo("gpt-5.6-luna", "xhigh"),
            Combo("gpt-5.6-luna", "max"),
            Combo("gpt-5.5", "high"),
            Combo("gpt-5.5", "xhigh"),
            Combo("deepseek-v4-flash", "max"),
            Combo("deepseek-v4-flash", "high"),
        )

        val PRO_COMBOS = listOf(
            Combo("deepseek-v4-pro", "max"),
            Combo("deepseek-v4-pro", "high"),
        )

        val ASTRA_COMBOS = listOf(
            Combo("gpt-6-astra", "low"),
            Combo("gpt-6-astra", "medium"),
            Combo("gpt-6-astra", "high"),
            Combo("gpt-6-astra", "xhigh"),
            Combo("gpt-6-astra", "max"),
            Combo("gpt-6-astra", "ultra"),
        )

        val CURRENT_COMBOS = listOf(
            Combo("gpt-5.6-sol", "low"), Combo("gpt-5.6-sol", "medium"), Combo("gpt-5.6-sol", "high"),
            Combo("gpt-5.6-sol", "xhigh"), Combo("gpt-5.6-sol", "max"), Combo("gpt-5.6-sol", "ultra"),
            Combo("gpt-5.6-terra", "low"), Combo("gpt-5.6-terra", "medium"), Combo("gpt-5.6-terra", "high"),
            Combo("gpt-5.6-terra", "xhigh"), Combo("gpt-5.6-terra", "max"), Combo("gpt-5.6-terra", "ultra"),
            Combo("gpt-5.6-luna", "low"), Combo("gpt-5.6-luna", "medium"), Combo("gpt-5.6-luna", "high"),
            Combo("gpt-5.6-luna", "xhigh"), Combo("gpt-5.6-luna", "max"),
            Combo("gpt-6-astra", "low"), Combo("gpt-6-astra", "medium"), Combo("gpt-6-astra", "high"),
            Combo("gpt-6-astra", "xhigh"), Combo("gpt-6-astra", "max"), Combo("gpt-6-astra", "ultra"),
            Combo("gpt-6-sol", "low"), Combo("gpt-6-sol", "medium"), Combo("gpt-6-sol", "high"),
            Combo("gpt-6-sol", "xhigh"), Combo("gpt-6-sol", "max"), Combo("gpt-6-sol", "ultra"),
            Combo("gpt-6-luna", "low"), Combo("gpt-6-luna", "medium"), Combo("gpt-6-luna", "high"),
            Combo("gpt-6-luna", "xhigh"), Combo("gpt-6-luna", "max"),
            Combo("gpt-5.5", "high"), Combo("gpt-5.5", "xhigh"),
            Combo("deepseek-v4-flash", "max"), Combo("deepseek-v4-flash", "high"),
            Combo("deepseek-v4.1-flash", "max"), Combo("deepseek-v4.1-flash", "high"),
            Combo("dsh-deepseek-v4-flash", "max"), Combo("dsh-deepseek-v4-flash", "high"),
            Combo("dsh-deepseek-v4.1-flash", "max"), Combo("dsh-deepseek-v4.1-flash", "high"),
            Combo("dsh-deepseek-v4-flash-vision-exp", "max"), Combo("dsh-deepseek-v4-flash-vision-exp", "high"),
        )
    }
}
