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
    fun `fetch calculates all 23 live IQ values and sends a fresh request every time`() {
        val http = RecordingHttpClient { jsonResponse(validPayload()) }
        val client = CodexRadarIqClient(http)

        val first = client.fetch().toCompletableFuture().join()
        val second = client.fetch().toCompletableFuture().join()

        assertEquals(23, first.size)
        assertEquals(first, second)
        assertEquals("GPT5.6 Sol", first.first().name)
        assertEquals("ultra", first.first().strength)
        assertEquals(75.0, first.first().iq)
        assertEquals(5, first.first().passedTasks)
        assertEquals(10, first.first().totalTasks)
        assertEquals("GPT5.6 Luna", first[12].name)
        assertEquals("max", first[12].strength)
        assertEquals("GPT5.5", first[17].name)
        assertEquals("xhigh", first[17].strength)
        assertEquals("DeepSeek V4 Flash", first[20].name)
        assertEquals("high", first[20].strength)
        assertEquals(30.0, first[20].iq)
        assertEquals("DeepSeek V4 Pro", first[21].name)
        assertEquals(MiersModelFamily.DEEPSEEK, first[21].family)
        assertEquals("max", first[21].strength)
        assertEquals("DeepSeek V4 Pro", first[22].name)
        assertEquals(MiersModelFamily.DEEPSEEK, first[22].family)
        assertEquals("high", first[22].strength)

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
        assertEquals(23, large.fetch().toCompletableFuture().join().size)

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
    fun `fetch rejects duplicate and missing model combinations`() {
        val duplicate = validPayload().replaceFirst("{", "{\"schema\":1,")
        assertRejected(CodexRadarIqClient(RecordingHttpClient { jsonResponse(duplicate) }), "duplicate fields")

        val missing = validPayload(combos = SITE_COMBOS.dropLast(1))
        assertRejected(CodexRadarIqClient(RecordingHttpClient { jsonResponse(missing) }))
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
    fun `fetch rejects tables whose model has no valid first-run sample`() {
        val unavailable = validPayload(noValidSamplesFor = "gpt-5.6-sol|low")

        assertRejected(
            CodexRadarIqClient(RecordingHttpClient { jsonResponse(unavailable) }),
            "no valid samples",
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
        combos: List<Combo> = SITE_COMBOS + PRO_COMBOS,
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
                        key == noValidSamplesFor -> "{}"
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
    }
}
