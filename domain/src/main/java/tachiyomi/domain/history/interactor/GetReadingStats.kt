package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.history.repository.HistoryRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Inject
class GetReadingStats(
    private val repository: HistoryRepository,
) {

    /** The titles you spent the most time on, longest first. */
    suspend fun mostRead(limit: Int = 5): List<Pair<String, Long>> = repository.getMostRead(limit.toLong())

    /**
     * Chapters last read on each of the past [days] days, oldest first, as (day of month, count).
     * A chapter counts once, on the day it was last read, so re-reading moves it to the later day.
     */
    suspend fun chaptersPerDay(days: Int = 7): List<Pair<Int, Int>> {
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val since = Clock.System.now() - days.days
        val perDay = repository.getReadAtSince(since.toEpochMilliseconds())
            .filter { it > 0L }
            .groupingBy { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }
            .eachCount()
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back)
            date.day to (perDay[date] ?: 0)
        }
    }

    private fun kotlinx.datetime.LocalDate.minusDays(n: Int) =
        kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() - n)
}
