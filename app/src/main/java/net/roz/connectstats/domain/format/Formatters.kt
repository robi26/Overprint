package net.roz.connectstats.domain.format

import net.roz.connectstats.domain.model.ActivityType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

class Formatters(private val metric: Boolean = true) {

    fun distance(meters: Double): String {
        return if (metric) {
            if (meters < 1000) "${meters.roundToInt()} m"
            else String.format(Locale.US, "%.2f km", meters / 1000.0)
        } else {
            val miles = meters / 1609.344
            if (miles < 0.2) "${(meters * 3.28084).roundToInt()} ft"
            else String.format(Locale.US, "%.2f mi", miles)
        }
    }

    fun distanceShort(meters: Double): String =
        if (metric) String.format(Locale.US, "%.1f", meters / 1000.0)
        else String.format(Locale.US, "%.1f", meters / 1609.344)

    fun distanceUnit(): String = if (metric) "km" else "mi"

    fun duration(seconds: Double): String {
        val s = seconds.roundToInt().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm <= 0 || !secPerKm.isFinite()) return "—"
        val value = if (metric) secPerKm else secPerKm * 1.609344
        val unit = if (metric) "/km" else "/mi"
        val total = value.roundToInt()
        val m = total / 60
        val s = total % 60
        return String.format(Locale.US, "%d:%02d%s", m, s, unit)
    }

    fun speed(mps: Double?): String {
        if (mps == null || mps <= 0) return "—"
        return if (metric) String.format(Locale.US, "%.1f km/h", mps * 3.6)
        else String.format(Locale.US, "%.1f mph", mps * 2.236936)
    }

    fun elevation(meters: Double?): String {
        if (meters == null) return "—"
        return if (metric) "${meters.roundToInt()} m"
        else "${(meters * 3.28084).roundToInt()} ft"
    }

    fun heartRate(bpm: Double?): String = bpm?.roundToInt()?.let { "$it bpm" } ?: "—"
    fun cadence(rpm: Double?): String = rpm?.roundToInt()?.let { "$it" } ?: "—"
    fun power(watts: Double?): String = watts?.roundToInt()?.let { "$it W" } ?: "—"
    fun calories(kcal: Double?): String = kcal?.roundToInt()?.let { "$it kcal" } ?: "—"
    fun grade(pct: Double?): String = pct?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"

    fun speedOrPace(type: ActivityType, mps: Double?, durationSec: Double, distanceM: Double): String {
        return if (type.usesPace) {
            val pace = if (distanceM > 1) durationSec / (distanceM / 1000.0) else
                mps?.takeIf { it > 0 }?.let { 1000.0 / it }
            pace(pace)
        } else speed(mps)
    }

    fun dateTime(millis: Long): String = dateTimeFmt.format(Date(millis))
    fun date(millis: Long): String = dateFmt.format(Date(millis))
    fun weekdayDate(millis: Long): String = weekdayFmt.format(Date(millis))
    fun time(millis: Long): String = timeFmt.format(Date(millis))
    fun monthYear(millis: Long): String = monthYearFmt.format(Date(millis))

    fun signed(value: Double, unit: String): String {
        val sign = if (value >= 0) "+" else "−"
        return "$sign${String.format(Locale.US, "%.1f", abs(value))} $unit"
    }

    companion object {
        private val dateTimeFmt = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        private val weekdayFmt = SimpleDateFormat("EEEE d MMM", Locale.getDefault())
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val monthYearFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    }
}
