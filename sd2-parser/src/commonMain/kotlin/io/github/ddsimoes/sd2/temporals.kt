@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.ddsimoes.sd2

import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

/**
 * Out-of-the-box temporal constructors for SD2.
 *
 * Supported constructors:
 * - date(string) -> LocalDate
 * - time(string) -> LocalTime (fractions up to 9 digits)
 * - instant(string) -> Instant (offset required)
 * - duration(string) -> kotlin.time.Duration (ISO subset: PnD, TnH nM n(.f)S)
 * - period(string) -> DateTimePeriod (Y/M/W/D; W mapped to 7D)
 */
internal object DefaultTemporalRegistry {
    val instance: ConstructorRegistry by lazy { build() }

    private fun build(): ConstructorRegistry {
        val b = ConstructorRegistryBuilder()
        // date
        b.register("date", "temporal.date") { call, ctx ->
            val s = (call.args.firstOrNull() as? Sd2Value.VString)?.value
                ?: ctx.error("E3001", "date expects a string", call.location)
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) ctx.error("E3001", "invalid date format", call.location)
            try {
                LocalDate.parse(s)
            } catch (_: Throwable) {
                ctx.error("E3001", "invalid date value", call.location)
            }
        }
        // time (fractions up to 9 digits)
        b.register("time", "temporal.time") { call, ctx ->
            val s = (call.args.firstOrNull() as? Sd2Value.VString)?.value
                ?: ctx.error("E3001", "time expects a string", call.location)
            val m = Regex("^\\d{2}:\\d{2}:\\d{2}(\\.(\\d+))?$").matchEntire(s)
                ?: ctx.error("E3001", "invalid time format", call.location)
            val frac = m.groupValues.getOrNull(2)
            if (!frac.isNullOrEmpty() && frac.length > 9) ctx.error(
                "E3003",
                "temporal fractions exceed 9 digits",
                call.location
            )
            try {
                LocalTime.parse(s)
            } catch (_: Throwable) {
                ctx.error("E3001", "invalid time value", call.location)
            }
        }
        // No datetime constructor per spec; use instant with offset
        // instant: offset required; fractions up to 9
        b.register("instant", "temporal.instant") { call, ctx ->
            val s = (call.args.firstOrNull() as? Sd2Value.VString)?.value
                ?: ctx.error("E3001", "instant expects a string", call.location)
            if (!(s.endsWith('Z') || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(s))) ctx.error(
                "E3001",
                "instant requires offset",
                call.location
            )
            val frac = Regex("\\.(\\d+)").find(s)?.groupValues?.getOrNull(1)
            if (frac != null && frac.length > 9) ctx.error("E3003", "temporal fractions exceed 9 digits", call.location)
            try {
                Instant.parse(s)
            } catch (_: Throwable) {
                ctx.error("E3001", "invalid instant format", call.location)
            }
        }
        // duration: ISO subset P[nD][T[nH][nM][n(.f)S]] -> kotlin.time.Duration
        b.register("duration", "temporal.duration") { call, ctx ->
            val s = (call.args.firstOrNull() as? Sd2Value.VString)?.value
                ?: ctx.error("E3001", "duration expects a string", call.location)
            if (!s.startsWith("P")) ctx.error("E3001", "invalid duration format", call.location)
            val body = s.substring(1)
            var datePart = body
            var timePart = ""
            if (body.contains('T')) {
                val idx = body.indexOf('T')
                datePart = body.take(idx)
                timePart = body.substring(idx + 1)
            }
            var totalNanos = 0L
            // Date part: only D (days)
            if (datePart.isNotEmpty()) {
                val m = Regex("^(\\d+)D$").matchEntire(datePart)
                if (m != null) {
                    val d = m.groupValues[1].toLong()
                    totalNanos += d * 86400L * 1_000_000_000L
                } else if (datePart.isNotEmpty()) {
                    ctx.error("E3004", "invalid calendar component in duration", call.location)
                }
            }
            if (timePart.isNotEmpty()) {
                val re = Regex("^((\\d+)H)?((\\d+)M)?((\\d+)(\\.(\\d{1,9}))?S)?$")
                val m =
                    re.matchEntire(timePart) ?: ctx.error("E3001", "invalid duration time components", call.location)
                val h = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
                val mm = m.groupValues[4].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
                val sWhole = m.groupValues[6].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
                val sFrac = m.groupValues[8].takeIf { it.isNotEmpty() }
                totalNanos += h * 3600L * 1_000_000_000L
                totalNanos += mm * 60L * 1_000_000_000L
                totalNanos += sWhole * 1_000_000_000L
                if (sFrac != null) {
                    // up to 9 digits: convert to nanoseconds fraction
                    val ns = (sFrac + "0".repeat(9 - sFrac.length)).take(9).toLong()
                    totalNanos += ns
                }
            }
            if (totalNanos == 0L && s != "PT0S" && s != "P0D") ctx.error("E3002", "empty duration", call.location)
            totalNanos.toDuration(DurationUnit.NANOSECONDS)
        }
        // period: Y/M/W/D (no T)
        b.register("period", "temporal.period") { call, ctx ->
            val s = (call.args.firstOrNull() as? Sd2Value.VString)?.value
                ?: ctx.error("E3001", "period expects a string", call.location)
            if (!s.startsWith("P")) ctx.error("E3001", "invalid period format", call.location)
            val body = s.substring(1)
            if (body.contains('T')) ctx.error("E3005", "time components not allowed in period", call.location)
            val re = Regex("^((\\d+)Y)?((\\d+)M)?((\\d+)W)?((\\d+)D)?$")
            val m = re.matchEntire(body) ?: ctx.error("E3001", "invalid period components", call.location)
            val years = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val months = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val weeks = m.groupValues[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val days = m.groupValues[8].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (years == 0 && months == 0 && weeks == 0 && days == 0 && s != "P0D" && s != "P0Y") ctx.error(
                "E3002",
                "empty period",
                call.location
            )
            DateTimePeriod(years = years, months = months, days = days + weeks * 7)
        }
        return b.build()
    }
}
