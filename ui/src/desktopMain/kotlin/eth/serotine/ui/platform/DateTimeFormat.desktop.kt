package eth.serotine.ui.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** java.time's localized medium date and short time, in the system's time zone. */
actual fun formatDateTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

/** java.time's hours, minutes, seconds and milliseconds, in the system's time zone as it is now. */
actual fun formatTimeOfDay(epochMillis: Long): String =
    TIME_OF_DAY.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

/** Parsed once: a debug log formats a time per line. Immutable, so any thread may use it. */
private val TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** java.time's localized short time, in the system's time zone. */
actual fun formatClockTime(epochSeconds: Long): String =
    CLOCK_TIME.withLocale(Locale.getDefault(Locale.Category.FORMAT)).withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

/** java.time's localized full date, in the system's time zone. */
actual fun formatLongDate(epochSeconds: Long): String =
    LONG_DATE.withLocale(Locale.getDefault(Locale.Category.FORMAT)).withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

/** The local date in the system's time zone, as java.time counts its epoch day. */
actual fun localEpochDay(epochSeconds: Long): Long =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/**
 * Made once, as an Activity times and dates every row; immutable, so any thread may use it. Each use takes the
 * current locale ([java.time.format.DateTimeFormatter.withLocale]): the user may change it while the process runs.
 */
private val CLOCK_TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Made once, as [CLOCK_TIME] is. */
private val LONG_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
