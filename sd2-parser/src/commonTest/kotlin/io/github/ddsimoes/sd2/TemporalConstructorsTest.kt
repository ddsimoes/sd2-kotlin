@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.ddsimoes.sd2

import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class TemporalConstructorsTest {

    private fun readAttrValue(input: String, attrName: String = "v"): Sd2Value {
        val r = Sd2.reader(StringSource(input))
        while (true) {
            when (val e = r.next()) {
                is Sd2Event.Attribute -> if (e.name.text == attrName) return e.value!!
                is Sd2Event.EndDocument -> error("attribute not found: $attrName")
                else -> {}
            }
        }
    }

    // date
    @Test fun date_valid() {
        val v = readAttrValue("item { v = date(\"2024-03-15\") }")
        val obj = assertIs<Sd2Value.VObject>(v)
        assertEquals(listOf("temporal","date"), obj.type.parts.map { it.text })
        assertIs<LocalDate>(obj.value)
        assertEquals(LocalDate.parse("2024-03-15"), obj.value)
    }
    @Test fun date_invalid_format() {
        assertFailsWith<ParseError> { readAttrValue("item { v = date(\"15-03-2024\") }") }
    }

    // time
    @Test fun time_valid_plain() {
        val v = readAttrValue("item { v = time(\"14:30:00\") }")
        val obj = assertIs<Sd2Value.VObject>(v)
        assertIs<LocalTime>(obj.value)
        assertEquals(LocalTime.parse("14:30:00"), obj.value)
    }
    @Test fun time_valid_fraction_max9() {
        val v = readAttrValue("item { v = time(\"14:30:00.123456789\") }")
        val obj = assertIs<Sd2Value.VObject>(v)
        assertEquals(LocalTime.parse("14:30:00.123456789"), obj.value)
    }
    @Test fun time_invalid_fraction_too_many() {
        assertFailsWith<ParseError> { readAttrValue("item { v = time(\"14:30:00.1234567890\") }") }
    }
    @Test fun time_invalid_value() {
        assertFailsWith<ParseError> { readAttrValue("item { v = time(\"24:00:00\") }") }
    }

    // instant
    @Test fun instant_valid_Z() {
        val v = readAttrValue("item { v = instant(\"2024-03-15T14:30:00Z\") }")
        val obj = assertIs<Sd2Value.VObject>(v)
        assertIs<kotlin.time.Instant>(obj.value)
        assertEquals("2024-03-15T14:30:00Z", obj.value.toString())
    }
    @Test fun instant_valid_offset() {
        val v = readAttrValue("item { v = instant(\"2024-03-15T14:30:00+02:00\") }")
        val obj = assertIs<Sd2Value.VObject>(v)
        assertIs<kotlin.time.Instant>(obj.value)
        assertEquals("2024-03-15T12:30:00Z", obj.value.toString()) // 14:30 at +02:00 == 12:30Z
    }
    @Test fun instant_missing_offset() {
        assertFailsWith<ParseError> { readAttrValue("item { v = instant(\"2024-03-15T14:30:00\") }") }
    }
    @Test fun instant_fraction_too_many() {
        assertFailsWith<ParseError> { readAttrValue("item { v = instant(\"2024-03-15T14:30:00.1234567890Z\") }") }
    }

    // duration
    @Test fun duration_seconds() {
        val v = readAttrValue("item { v = duration(\"PT30S\") }")
        val d = assertIs<Sd2Value.VObject>(v).value as Duration
        assertEquals(30.toDuration(DurationUnit.SECONDS), d)
    }
    @Test fun duration_half_second() {
        val v = readAttrValue("item { v = duration(\"PT0.5S\") }")
        val d = assertIs<Sd2Value.VObject>(v).value as Duration
        assertEquals(500_000_000L.toDuration(DurationUnit.NANOSECONDS), d)
    }
    @Test fun duration_one_day_and_complex() {
        val day = (assertIs<Sd2Value.VObject>(readAttrValue("item { v = duration(\"P1D\") }")).value as Duration)
        assertEquals((24L * 3600L).toDuration(DurationUnit.SECONDS), day)
        val complex = (assertIs<Sd2Value.VObject>(readAttrValue("item { v = duration(\"P1DT2H30M\") }")).value as Duration)
        val expected = (24L * 3600L).toDuration(DurationUnit.SECONDS) + (2L * 3600L).toDuration(DurationUnit.SECONDS) + (30L * 60L).toDuration(DurationUnit.SECONDS)
        assertEquals(expected, complex)
    }
    @Test fun duration_invalid_calendar_components() {
        assertFailsWith<ParseError> { readAttrValue("item { v = duration(\"P1Y\") }") }
        assertFailsWith<ParseError> { readAttrValue("item { v = duration(\"P2W\") }") }
    }
    @Test fun duration_empty_components() {
        assertFailsWith<ParseError> { readAttrValue("item { v = duration(\"P\") }") }
        assertFailsWith<ParseError> { readAttrValue("item { v = duration(\"PT\") }") }
    }

    // period
    @Test fun period_valid_mixed() {
        val v = readAttrValue("item { v = period(\"P1Y2M3W4D\") }")
        val p = assertIs<DateTimePeriod>((assertIs<Sd2Value.VObject>(v)).value)
        assertEquals(1, p.years)
        assertEquals(2, p.months)
        assertEquals(3*7 + 4, p.days)
    }
    @Test fun period_zero_allowed() {
        val v = readAttrValue("item { v = period(\"P0D\") }")
        val p = assertIs<DateTimePeriod>((assertIs<Sd2Value.VObject>(v)).value)
        assertEquals(0, p.years)
        assertEquals(0, p.months)
        assertEquals(0, p.days)
    }
    @Test fun period_invalid_with_time_components_or_empty() {
        assertFailsWith<ParseError> { readAttrValue("item { v = period(\"PT30S\") }") }
        assertFailsWith<ParseError> { readAttrValue("item { v = period(\"P\") }") }
    }
}
