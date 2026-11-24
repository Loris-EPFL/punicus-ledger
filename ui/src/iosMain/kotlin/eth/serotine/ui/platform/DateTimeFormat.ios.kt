package eth.serotine.ui.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterFullStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.autoupdatingCurrentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

/** Foundation's medium date and short time, in the device's time zone and locale. */
actual fun formatDateTime(epochSeconds: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterMediumStyle
    formatter.timeStyle = NSDateFormatterShortStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))
}

/** Foundation's hours, minutes, seconds and milliseconds, in the device's time zone as it is now. */
actual fun formatTimeOfDay(epochMillis: Long): String =
    timeOfDay.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1_000.0))

/**
 * Made once, as a debug log formats a time per line (a formatter is costly to make, and safe to
 * share between threads since iOS 7). A fixed format in en_US_POSIX, so the user's 12-hour setting
 * cannot turn `HH` into an hour with AM/PM (Apple's QA1480); the time zone follows the device's.
 */
private val timeOfDay: NSDateFormatter by lazy {
    NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        timeZone = NSTimeZone.localTimeZone
        dateFormat = "HH:mm:ss.SSS"
    }
}

/** Foundation's short time, in the device's time zone and locale. */
actual fun formatClockTime(epochSeconds: Long): String =
    clockTime.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))

/** Foundation's full date, in the device's time zone and locale. */
actual fun formatLongDate(epochSeconds: Long): String =
    longDate.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))

/**
 * Made once, as an Activity times every row (see [timeOfDay]); its locale and time zone follow the user's settings as
 * they change.
 */
private val clockTime: NSDateFormatter by lazy {
    NSDateFormatter().apply {
        locale = NSLocale.autoupdatingCurrentLocale
        timeZone = NSTimeZone.localTimeZone
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
    }
}

/** Made once, as [clockTime] is. */
private val longDate: NSDateFormatter by lazy {
    NSDateFormatter().apply {
        locale = NSLocale.autoupdatingCurrentLocale
        timeZone = NSTimeZone.localTimeZone
        dateStyle = NSDateFormatterFullStyle
        timeStyle = NSDateFormatterNoStyle
    }
}

/** The local day: the moment shifted by the device's time zone's offset from UTC at that moment, in whole days. */
actual fun localEpochDay(epochSeconds: Long): Long {
    val moment = NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())
    val offset: Long = NSTimeZone.localTimeZone.secondsFromGMTForDate(moment)
    return (epochSeconds + offset).floorDiv(SECONDS_PER_DAY)
}

private const val SECONDS_PER_DAY = 86_400L
