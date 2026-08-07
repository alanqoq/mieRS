package com.mieai.qqbot.plugin.miers

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface MiersCooldownDecision {
    data class Accepted internal constructor(
        internal val reservation: MiersCooldownReservation?,
    ) : MiersCooldownDecision

    data class Limited(val remaining: Duration) : MiersCooldownDecision {
        init {
            require(!remaining.isNegative && !remaining.isZero) { "remaining must be positive" }
        }
    }
}

internal data class MiersCooldownReservation(
    val userId: String,
    val previousNextAllowedAt: Instant?,
    val reservedNextAllowedAt: Instant,
)

/**
 * Thread-safe per-user cooldown tracker.
 *
 * Successful reservations can be rolled back when queue admission fails. The
 * expected reserved timestamp prevents an old rollback from overwriting a newer
 * reservation for the same user.
 */
class MiersCooldownTracker {
    private val lock = ReentrantLock()
    private val nextAllowedAt = HashMap<String, Instant>()

    fun reserve(
        userId: String?,
        now: Instant,
        cooldownMinutes: Int,
    ): MiersCooldownDecision {
        require(cooldownMinutes >= 0) { "cooldownMinutes must be non-negative" }
        if (cooldownMinutes == 0 || userId.isNullOrBlank()) {
            return MiersCooldownDecision.Accepted(null)
        }

        return lock.withLock {
            val previous = nextAllowedAt[userId]
            if (previous != null && now.isBefore(previous)) {
                return@withLock MiersCooldownDecision.Limited(Duration.between(now, previous))
            }

            val next = now.plus(cooldownMinutes.toLong(), ChronoUnit.MINUTES)
            nextAllowedAt[userId] = next
            MiersCooldownDecision.Accepted(
                MiersCooldownReservation(
                    userId = userId,
                    previousNextAllowedAt = previous,
                    reservedNextAllowedAt = next,
                ),
            )
        }
    }

    fun rollback(decision: MiersCooldownDecision.Accepted) {
        val reservation = decision.reservation ?: return
        lock.withLock {
            if (nextAllowedAt[reservation.userId] != reservation.reservedNextAllowedAt) return
            val previous = reservation.previousNextAllowedAt
            if (previous == null) {
                nextAllowedAt.remove(reservation.userId)
            } else {
                nextAllowedAt[reservation.userId] = previous
            }
        }
    }

    fun clear() {
        lock.withLock(nextAllowedAt::clear)
    }
}
