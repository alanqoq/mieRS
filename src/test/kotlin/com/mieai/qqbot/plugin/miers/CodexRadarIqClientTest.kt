package com.mieai.qqbot.plugin.miers

import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexRadarIqClientTest {
    @Test
    fun `fetch reads current public scores with bounded concurrency and skips unscored tiers`() {
        val http = QueuedHttpClient()
        val result = CodexRadarIqClient(http).fetch().toCompletableFuture()

        while (!result.isDone) {
            awaitCondition { http.pending().isNotEmpty() || result.isDone }
            http.pending().forEach { pending ->
                val selection = query(pending.request.uri)
                val response = if (selection["model"] == "gpt-5.6-luna" && selection["effort"] == "high") {
                    scorePayload(selection.getValue("model"), selection.getValue("effort"), null, coverage = 0)
                } else {
                    scorePayload(
                        selection.getValue("model"),
                        selection.getValue("effort"),
                        if (selection["model"] == "gpt-6-astra" && selection["effort"] == "ultra") 150.0 else 81.75471971832992,
                    )
                }
                http.complete(pending, jsonResponse(response))
            }
        }

        val models = result.join()
        val requests = http.requests()
        assertEquals(33, models.size)
        assertEquals(34, requests.size)
        assertEquals(3, http.maxActive.get())
        assertEquals(150.0, models.first().iq)
        assertEquals("GPT-6 Astra", models.first().name)
        assertEquals("ultra", models.first().strength)
        assertEquals(64, models.first().coveredTasks)
        assertEquals(64, models.first().requiredTasks)
        assertTrue(models.any { it.name == "GPT-6.1 Sol" && it.strength == "low" })
        assertTrue(models.none { it.name == "GPT-5.6 Luna" && it.strength == "high" })

        val selections = requests.map { request ->
            assertEquals("GET", request.method)
            assertEquals("api.codexradar.com", request.uri.host)
            assertEquals("/api/v1/radar-bench-score", request.uri.path)
            assertEquals("application/json", request.headers["Accept"])
            assertEquals("no-cache", request.headers["Cache-Control"])
            assertEquals("user-selected-v1", request.headers["X-Radar-Bench-Selection"])
            assertEquals(20, request.timeout.seconds)
            val selection = query(request.uri)
            assertEquals("summary", selection["view"])
            "${selection.getValue("model")}/${selection.getValue("effort")}"
        }
        assertEquals(34, selections.toSet().size)
    }

    @Test
    fun `fetch keeps scored results when one configuration request fails`() {
        val http = RecordingHttpClient { request ->
            val selection = query(request.uri)
            if (selection["model"] == "gpt-6-astra" && selection["effort"] == "ultra") {
                PluginHttpResponse(502, jsonHeaders(), "Bad Gateway".toByteArray())
            } else {
                jsonResponse(scorePayload(selection.getValue("model"), selection.getValue("effort")))
            }
        }

        val models = CodexRadarIqClient(http).fetch().toCompletableFuture().join()

        assertEquals(33, models.size)
        assertEquals(34, http.requests().size)
        assertTrue(models.none { it.name == "GPT-6 Astra" && it.strength == "ultra" })
    }

    @Test
    fun `fetch rejects all failed requests and invalid summaries`() {
        val unavailable = CodexRadarIqClient(
            RecordingHttpClient { PluginHttpResponse(502, jsonHeaders(), "Bad Gateway".toByteArray()) },
        )
        assertRejected(unavailable, "HTTP 502")

        val malformed = CodexRadarIqClient(RecordingHttpClient { jsonResponse("{not JSON") })
        assertRejected(malformed, "JSON is invalid")

        val wrongSelection = CodexRadarIqClient(
            RecordingHttpClient { jsonResponse(scorePayload("wrong-model", "low")) },
        )
        assertRejected(wrongSelection, "does not match")

        val wrongContentType = CodexRadarIqClient(
            RecordingHttpClient {
                PluginHttpResponse(200, mapOf("Content-Type" to listOf("text/html")), "{}".toByteArray())
            },
        )
        assertRejected(wrongContentType, "content type")
    }

    @Test
    fun `fetch rejects invalid score metadata and trailing JSON`() {
        val wrongVersion = CodexRadarIqClient(
            RecordingHttpClient { request ->
                val selection = query(request.uri)
                jsonResponse(scorePayload(selection.getValue("model"), selection.getValue("effort"))
                    .replace("radar-bench-v2-150", "unknown"))
            },
        )
        assertRejected(wrongVersion, "score_version")

        val invalidCoverage = CodexRadarIqClient(
            RecordingHttpClient { request ->
                val selection = query(request.uri)
                jsonResponse(scorePayload(selection.getValue("model"), selection.getValue("effort"), coverage = 65))
            },
        )
        assertRejected(invalidCoverage, "coverage is outside")

        val trailing = CodexRadarIqClient(
            RecordingHttpClient { request ->
                val selection = query(request.uri)
                jsonResponse(scorePayload(selection.getValue("model"), selection.getValue("effort")) + " true")
            },
        )
        assertRejected(trailing)
    }

    private fun assertRejected(client: CodexRadarIqClient, expectedMessage: String? = null) {
        val failure = assertFailsWith<CompletionException> { client.fetch().toCompletableFuture().join() }
        val cause = assertIs<CodexRadarIqException>(failure.cause)
        if (expectedMessage != null) assertTrue(cause.message.orEmpty().contains(expectedMessage))
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&')
        .associate { part -> part.substringBefore('=') to part.substringAfter('=') }

    private fun scorePayload(model: String, effort: String, score: Double? = 81.75471971832992, coverage: Int = 64): String {
        val scoreValue = score?.toString() ?: "null"
        return """
            {"schema":1,"benchmark":"radar-bench-final64-20261004","score_version":"radar-bench-v2-150",
             "score_max":150.0,"model":"$model","effort":"$effort","harness":"codex",
             "score":$scoreValue,"coverage":$coverage,"required_tasks":64,"score_status":"complete"}
        """.trimIndent()
    }

    private fun jsonResponse(content: String): PluginHttpResponse =
        PluginHttpResponse(200, jsonHeaders(), content.toByteArray(StandardCharsets.UTF_8))

    private fun jsonHeaders(): Map<String, List<String>> =
        mapOf("content-type" to listOf("application/json; charset=utf-8"))

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), "condition did not become true")
    }

    private data class PendingRequest(
        val request: PluginHttpRequest,
        val response: CompletableFuture<PluginHttpResponse>,
    )

    private class QueuedHttpClient : PluginHttpClient {
        private val pending = mutableListOf<PendingRequest>()
        private val active = AtomicInteger()
        val maxActive = AtomicInteger()

        override fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse> {
            val response = CompletableFuture<PluginHttpResponse>()
            val count = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, count) }
            synchronized(pending) { pending += PendingRequest(request, response) }
            return response
        }

        fun complete(pendingRequest: PendingRequest, response: PluginHttpResponse) {
            active.decrementAndGet()
            pendingRequest.response.complete(response)
        }

        fun pending(): List<PendingRequest> = synchronized(pending) { pending.filterNot { it.response.isDone } }
        fun requests(): List<PluginHttpRequest> = synchronized(pending) { pending.map(PendingRequest::request) }
    }

    private class RecordingHttpClient(
        private val responder: (PluginHttpRequest) -> PluginHttpResponse,
    ) : PluginHttpClient {
        private val requests = mutableListOf<PluginHttpRequest>()

        override fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse> {
            synchronized(requests) { requests += request }
            return try {
                CompletableFuture.completedFuture(responder(request))
            } catch (failure: RuntimeException) {
                CompletableFuture.failedFuture(failure)
            }
        }

        fun requests(): List<PluginHttpRequest> = synchronized(requests) { requests.toList() }
    }
}
