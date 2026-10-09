package app.pareido.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.Instant

/** Rough API prices (USD per million tokens). Estimates only — the Anthropic Console has the real bill. */
object Pricing {
    data class Rates(val inputPerM: Double, val outputPerM: Double, val cacheReadPerM: Double, val cacheWritePerM: Double)

    private val table = mapOf(
        "claude-opus-5-5" to Rates(4.00, 20.00, 0.20, 5.00),
        "claude-sonnet-5-5" to Rates(2.00, 10.00, 0.20, 2.50),
        "claude-haiku-5-5" to Rates(0.10, 0.50, 0.01, 0.125),
    )

    /** Unknown models (e.g. a fallback) are priced like Opus so the estimate errs high. */
    fun ratesFor(model: String): Rates =
        table.entries.firstOrNull { model.startsWith(it.key) }?.value ?: table.getValue("claude-opus-5-5")

    fun costUsd(usage: TokenUsage): Double {
        val r = ratesFor(usage.model)
        return (usage.inputTokens * r.inputPerM +
            usage.outputTokens * r.outputPerM +
            usage.cacheReadTokens * r.cacheReadPerM +
            usage.cacheWriteTokens * r.cacheWritePerM) / 1_000_000.0
    }
}

data class SpendingSummary(
    val analyses: Int,
    val totalUsd: Double,
    val thisMonthAnalyses: Int,
    val thisMonthUsd: Double,
)

object Spending {
    fun summarize(records: List<UsageRecord>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): SpendingSummary {
        val month = YearMonth.from(now.atZone(zone))
        val thisMonth = records.filter { YearMonth.from(Instant.ofEpochMilli(it.atMillis).atZone(zone)) == month }
        return SpendingSummary(
            analyses = records.size,
            totalUsd = records.sumOf { Pricing.costUsd(it.usage) },
            thisMonthAnalyses = thisMonth.size,
            thisMonthUsd = thisMonth.sumOf { Pricing.costUsd(it.usage) },
        )
    }
}

data class Streak(val current: Int, val best: Int, val spottedToday: Boolean)

object Streaks {
    /**
     * Days in a row with at least one find. The current streak survives until the
     * end of today, so a streak ending yesterday still counts.
     */
    fun compute(days: Collection<LocalDate>, today: LocalDate): Streak {
        val set = days.toSortedSet()
        if (set.isEmpty()) return Streak(0, 0, false)

        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in set) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }

        val spottedToday = today in set
        var cursor = if (spottedToday) today else today.minusDays(1)
        var current = 0
        while (cursor in set) {
            current++
            cursor = cursor.minusDays(1)
        }
        return Streak(current, best, spottedToday)
    }
}
