package eth.serotine.ui.activity

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityDaysTest {

    // A day is 100 seconds here: 600 is day 6, today.
    private val dates = ActivityDates(dayOf = { it / 100 }, dayName = { "day ${it / 100}" }, clock = { "at $it" })

    private fun row(key: String) = ActivityRow(key, key, "", "", "", "", ActivityState.DONE, ActivityKey(key))

    @Test
    fun eachDatedRowHasItsDaysHeadingTodayAndYesterdaySaidAndItsTime() {
        val rows = listOf(Placed(row("a"), 650), Placed(row("b"), 520), Placed(row("c"), 310)).byDay(dates, now = 699)

        assertEquals(listOf("Today · day 6", "Yesterday · day 5", "day 3"), rows.map { it.day })
        assertEquals(listOf("at 650", "at 520", "at 310"), rows.map { it.clock })
    }

    @Test
    fun aRowDatedByItsBlockAloneTakesTheDayOfTheRowsAroundItOnlyWhenTheirBlocksTimesSayIt() {
        val rows = listOf(
            Placed(row("a"), 560, anchored = true),
            Placed(row("between same day")),
            Placed(row("b"), 510, anchored = true),
            Placed(row("between two days")),
            Placed(row("c"), 450, anchored = true),
            Placed(row("next to a peer's time")),
            Placed(row("d"), 420, anchored = false),
            Placed(row("oldest")),
        ).byDay(dates, now = 600)

        assertEquals(
            listOf(
                "Yesterday · day 5",
                "Yesterday · day 5",
                "Yesterday · day 5",
                UNDATED_DAY,
                "day 4",
                UNDATED_DAY,
                "day 4",
                UNDATED_DAY,
            ),
            rows.map { it.day },
        )
        assertEquals(null, rows[1].clock, "no time for a row dated by its block alone")
        assertEquals(UNDATED_SIMPLE_DAY, rows[3].simpleDay)
        assertEquals(null, rows[1].simpleDay, "a day told from its neighbours is said alike in simple mode")
    }

    @Test
    fun aGhostWhoseDateCannotBeToldGoesUnderTheDayOfTheRowAboveItElseOfTheFirstBelow() {
        fun ghost(key: String) = row(key).copy(pending = true)
        val rows = listOf(
            Placed(ghost("newest ghost")),
            Placed(ghost("second ghost")),
            Placed(row("a"), 560, anchored = true),
            Placed(ghost("between two days")),
            Placed(row("b"), 450, anchored = true),
            Placed(row("undated event")),
            Placed(ghost("below an undated event")),
        ).byDay(dates, now = 600)

        assertEquals(
            listOf(
                "Yesterday · day 5",
                "Yesterday · day 5",
                "Yesterday · day 5",
                "Yesterday · day 5",
                "day 4",
                UNDATED_DAY,
                UNDATED_DAY,
            ),
            rows.map { it.day },
            "no heading of its own: its row, dated once checked, takes its place",
        )
        assertEquals(null, rows[0].simpleDay)
        assertEquals(UNDATED_SIMPLE_DAY, rows[6].simpleDay, "a ghost below an undated row stays among them")
    }
}
