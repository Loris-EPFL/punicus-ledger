package eth.serotine.ui.activity

import eth.serotine.ui.platform.formatClockTime
import eth.serotine.ui.platform.formatLongDate
import eth.serotine.ui.platform.localEpochDay

/**
 * How an Activity's rows are grouped by day and timed (owner's request, 2026-11-23): [dayOf] the day a moment (unix
 * seconds) falls on, as a number one higher each day; [dayName] that day in words; [clock] its time of day. In the
 * user's own time zone and locale by default; a test hands in its own.
 */
class ActivityDates(
    val dayOf: (Long) -> Long = ::localEpochDay,
    val dayName: (Long) -> String = ::formatLongDate,
    val clock: (Long) -> String = ::formatClockTime,
)

/**
 * A row as the list places it: the [moment] it goes by (unix seconds), null for a row dated by its block alone, and
 * whether that moment is its block's time ([anchored]), as the block's verified header says, rather than when a peer
 * took it or when it was signed.
 */
internal class Placed(val row: ActivityRow, val moment: Long? = null, val anchored: Boolean = false)

/**
 * These rows, as the list places them by date, as it groups them: each with its day's heading, "Today" and
 * "Yesterday" said before the date as of [now], and its time of day. A row dated by its block alone has no time; it
 * goes under the day of the rows around it when the rows just above and below are dated by their blocks' times and
 * on the same day (block times never run backwards, so its block's time lies between theirs), else under a heading
 * that says its date is not reported. The list draws a heading wherever it changes, so a day's heading comes again
 * after rows whose date is not reported.
 */
internal fun List<Placed>.byDay(dates: ActivityDates, now: Long): List<ActivityRow> {
    val today = dates.dayOf(now)
    fun heading(moment: Long): String {
        val name = dates.dayName(moment)
        return when (dates.dayOf(moment)) {
            today -> "Today · $name"
            today - 1 -> "Yesterday · $name"
            else -> name
        }
    }
    // The nearest dated row above and below each row, found in one pass each way.
    val above = arrayOfNulls<Placed>(size)
    val below = arrayOfNulls<Placed>(size)
    var last: Placed? = null
    for (at in indices) {
        above[at] = last
        if (this[at].moment != null) last = this[at]
    }
    last = null
    for (at in indices.reversed()) {
        below[at] = last
        if (this[at].moment != null) last = this[at]
    }
    val placed = mapIndexed { at, placed ->
        val moment = placed.moment
        when {
            moment != null -> placed.row.copy(day = heading(moment), simpleDay = null, clock = dates.clock(moment))
            else -> {
                val day = between(above[at], below[at], dates)?.let(::heading)
                if (day != null) {
                    placed.row.copy(day = day, simpleDay = null, clock = null)
                } else {
                    placed.row.copy(day = UNDATED_DAY, simpleDay = UNDATED_SIMPLE_DAY, clock = null)
                }
            }
        }
    }
    return placed.ghostsUnderTheirNeighboursDay()
}

/**
 * These rows with each ghost (an entry still being checked) whose date cannot be told under the day of the row just
 * above it, else, at the top, of the first row below it whose day is told: its row, dated by its block once checked,
 * then takes its place without a heading of "date not reported" coming and going around it. A ghost with only such
 * rows around it keeps that heading.
 */
private fun List<ActivityRow>.ghostsUnderTheirNeighboursDay(): List<ActivityRow> {
    fun untold(row: ActivityRow) = row.pending && row.day == UNDATED_DAY
    if (none(::untold)) return this
    val out = toMutableList()
    for (at in out.indices) {
        if (!untold(out[at])) continue
        val neighbour = out.getOrNull(at - 1) ?: drop(at + 1).firstOrNull { !untold(it) } ?: continue
        out[at] = out[at].copy(day = neighbour.day, simpleDay = neighbour.simpleDay)
    }
    return out
}

/**
 * A moment on the day a row between [above] and [below] falls on, when both are dated by their blocks' times and on
 * the same day; null when that cannot be told.
 */
private fun between(above: Placed?, below: Placed?, dates: ActivityDates): Long? {
    val newer = above?.takeIf { it.anchored }?.moment ?: return null
    val older = below?.takeIf { it.anchored }?.moment ?: return null
    return older.takeIf { dates.dayOf(newer) == dates.dayOf(older) }
}

/** The heading of rows the engine dated by their block alone: they go by it, among the dated ones. */
internal const val UNDATED_DAY = "Date not reported: in block order"

/** [UNDATED_DAY] in simple mode's words, with no block. */
internal const val UNDATED_SIMPLE_DAY = "Date not available"
