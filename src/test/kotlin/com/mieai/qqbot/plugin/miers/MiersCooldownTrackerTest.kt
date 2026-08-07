package com.mieai.qqbot.plugin.miers

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MiersCooldownTrackerTest {
    @Test
    fun `first use is accepted then limited until the exact deadline`() {
        val tracker = MiersCooldownTracker()
        val start = Instant.parse("2026-08-05T12:00:00Z")

        assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("user-1", start, 5))
        val limited = assertIs<MiersCooldownDecision.Limited>(
            tracker.reserve("user-1", start.plusSeconds(61), 5),
        )
        assertEquals(Duration.ofSeconds(239), limited.remaining)
        assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("user-1", start.plusSeconds(300), 5))
    }

    @Test
    fun `zero cooldown and missing user identity never create a limit`() {
        val tracker = MiersCooldownTracker()
        val now = Instant.parse("2026-08-05T12:00:00Z")

        repeat(2) {
            assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("user-1", now, 0))
            assertIs<MiersCooldownDecision.Accepted>(tracker.reserve(null, now, 5))
            assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("  ", now, 5))
        }
    }

    @Test
    fun `rollback releases a rejected queue admission`() {
        val tracker = MiersCooldownTracker()
        val now = Instant.parse("2026-08-05T12:00:00Z")
        val accepted = assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("user-1", now, 5))

        tracker.rollback(accepted)

        assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("user-1", now, 5))
    }

    @Test
    fun `stale rollback cannot overwrite a newer reservation`() {
        val tracker = MiersCooldownTracker()
        val start = Instant.parse("2026-08-05T12:00:00Z")
        val first = assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("user-1", start, 1))
        assertIs<MiersCooldownDecision.Accepted>(tracker.reserve("user-1", start.plusSeconds(60), 1))

        tracker.rollback(first)

        val limited = assertIs<MiersCooldownDecision.Limited>(
            tracker.reserve("user-1", start.plusSeconds(90), 1),
        )
        assertEquals(Duration.ofSeconds(30), limited.remaining)
    }

    @Test
    fun `concurrent reservations allow only one request per user`() {
        val tracker = MiersCooldownTracker()
        val now = Instant.parse("2026-08-05T12:00:00Z")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val decisions = executor.invokeAll(
                List(32) { Callable { tracker.reserve("user-1", now, 1) } },
            ).map { it.get() }

            assertEquals(1, decisions.count { it is MiersCooldownDecision.Accepted })
            assertEquals(31, decisions.count { it is MiersCooldownDecision.Limited })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `negative cooldown is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MiersCooldownTracker().reserve("user-1", Instant.EPOCH, -1)
        }
    }
}
