package com.meteor.memory.ui.life

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class BirthdayMetrics(
    val ageYears: Double,
    val elapsedHours: Long,
    val secondsUntilBirthday: Long,
    val nextBirthday: LocalDate,
)

internal object BirthdayAgeCalculator {
    private const val SECONDS_PER_MEAN_YEAR = 365.2425 * 24 * 60 * 60

    fun nextBirthday(
        profile: BirthProfile,
        now: Instant,
        zone: ZoneId,
        lunarCalendar: ChineseLunarCalendar? = null,
    ): LocalDate = when (profile) {
        is BirthProfile.Solar -> nextSolarBirthday(profile.gregorianDate, now, zone)
        is BirthProfile.Lunar -> requireNotNull(
            requireNotNull(lunarCalendar) { "A lunar calendar is required" }
                .nextBirthday(profile, now, zone),
        ) { "Could not resolve the next lunar birthday" }
    }

    fun calculate(
        profile: BirthProfile,
        now: Instant,
        zone: ZoneId,
        nextBirthday: LocalDate,
    ): BirthdayMetrics {
        val birthInstant = profile.gregorianDate.atStartOfDay(zone).toInstant()
        require(!birthInstant.isAfter(now)) { "Birth date cannot be in the future" }

        val birthdayInstant = nextBirthday.atStartOfDay(zone).toInstant()
        val elapsedSeconds = Duration.between(birthInstant, now).seconds
        val secondsUntilBirthday = Duration.between(now, birthdayInstant).seconds.coerceAtLeast(0)

        return BirthdayMetrics(
            ageYears = elapsedSeconds / SECONDS_PER_MEAN_YEAR,
            elapsedHours = elapsedSeconds / 3600,
            secondsUntilBirthday = secondsUntilBirthday,
            nextBirthday = nextBirthday,
        )
    }

    private fun nextSolarBirthday(birthDate: LocalDate, now: Instant, zone: ZoneId): LocalDate {
        for (year in now.atZone(zone).year..(now.atZone(zone).year + 2)) {
            val day = minOf(birthDate.dayOfMonth, LocalDate.of(year, birthDate.month, 1).lengthOfMonth())
            val candidate = LocalDate.of(year, birthDate.month, day)
            if (!candidate.atStartOfDay(zone).toInstant().isBefore(now)) return candidate
        }
        error("Could not resolve the next solar birthday")
    }
}
