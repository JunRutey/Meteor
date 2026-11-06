package com.meteor.memory.ui.life

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BirthdayAgeCalculatorTest {

    @Test
    fun `elapsed hours and fractional years use actual elapsed seconds`() {
        val profile = BirthProfile.Solar(LocalDate.of(2000, 1, 1))
        val now = Instant.parse("2000-01-02T01:00:00Z")
        val metrics = BirthdayAgeCalculator.calculate(
            profile = profile,
            now = now,
            zone = ZoneId.of("UTC"),
            nextBirthday = LocalDate.of(2001, 1, 1),
        )

        assertEquals(25L, metrics.elapsedHours)
        assertEquals(90_000.0 / (365.2425 * 24 * 60 * 60), metrics.ageYears, 0.0000000001)
        assertEquals(
            java.time.Duration.between(now, Instant.parse("2001-01-01T00:00:00Z")).seconds,
            metrics.secondsUntilBirthday,
        )
    }

    @Test
    fun `birthday at local midnight has zero countdown`() {
        val profile = BirthProfile.Solar(LocalDate.of(2000, 6, 15))
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.of(2025, 6, 15).atStartOfDay(zone).toInstant()
        val metrics = BirthdayAgeCalculator.calculate(
            profile = profile,
            now = now,
            zone = zone,
            nextBirthday = LocalDate.of(2025, 6, 15),
        )

        assertEquals(0L, metrics.secondsUntilBirthday)
        assertTrue(metrics.elapsedHours > 0)
    }

    @Test
    fun `elapsed hours follow local daylight saving time`() {
        val zone = ZoneId.of("America/New_York")
        val profile = BirthProfile.Solar(LocalDate.of(2021, 3, 13))
        val now = LocalDate.of(2021, 3, 15).atStartOfDay(zone).toInstant()
        val metrics = BirthdayAgeCalculator.calculate(
            profile = profile,
            now = now,
            zone = zone,
            nextBirthday = LocalDate.of(2022, 3, 13),
        )

        assertEquals(47L, metrics.elapsedHours)
    }

    @Test
    fun `solar leap day birthday uses last day of February in non leap years`() {
        val profile = BirthProfile.Solar(LocalDate.of(2000, 2, 29))
        val now = Instant.parse("2021-02-28T00:00:01Z")

        val birthday = BirthdayAgeCalculator.nextBirthday(
            profile = profile,
            now = now,
            zone = ZoneId.of("UTC"),
        )

        assertEquals(LocalDate.of(2022, 2, 28), birthday)
    }
}
