package eth.serotine.ui.platform

/**
 * [epochSeconds] (unix seconds) as a date and time in the user's own time zone and locale,
 * for a sentence: "Nov 6, 2026, 3:00 PM" in English. What the screens and notifications date
 * a network upgrade with.
 */
expect fun formatDateTime(epochSeconds: Long): String

/**
 * [epochMillis] (unix milliseconds) as the time of day in the user's own time zone, to the
 * millisecond: "14:03:27.481". What the Debug tab dates its log records with.
 */
expect fun formatTimeOfDay(epochMillis: Long): String

/**
 * [epochSeconds] (unix seconds) as the time of day in the user's own time zone and locale, to the minute: "3:00 PM"
 * in English. What a row of a list grouped by day ([formatLongDate]) is timed with.
 */
expect fun formatClockTime(epochSeconds: Long): String

/**
 * [epochSeconds] (unix seconds) as its date in the user's own time zone and locale, weekday and all: "Monday,
 * November 23, 2026" in English. What a list grouped by day heads each day with.
 */
expect fun formatLongDate(epochSeconds: Long): String

/**
 * The day [epochSeconds] (unix seconds) falls on in the user's own time zone, as days since 1970-01-01 there: two
 * moments are on the same day when they give the same number, and the day before has the number one less.
 */
expect fun localEpochDay(epochSeconds: Long): Long
