package com.meteor.memory.ui.life

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.LocalDate

sealed interface BirthProfile {
    val gregorianDate: LocalDate

    data class Solar(override val gregorianDate: LocalDate) : BirthProfile {
        init {
            require(gregorianDate.year >= MIN_BIRTH_YEAR)
        }
    }

    data class Lunar(
        val year: Int,
        val month: Int,
        val day: Int,
        val leapMonth: Boolean,
        override val gregorianDate: LocalDate,
    ) : BirthProfile {
        init {
            require(year >= MIN_BIRTH_YEAR)
            require(month in 1..12)
            require(day in 1..30)
            require(gregorianDate.year >= MIN_BIRTH_YEAR)
            // A lunar year starts in January/February, so its Gregorian
            // counterpart is always inside `year` or `year + 1`. This catches a
            // stored file whose two halves disagree without re-running ICU.
            require(gregorianDate.year == year || gregorianDate.year == year + 1) {
                "Lunar birth date does not match its Gregorian year"
            }
        }
    }

    companion object {
        const val MIN_BIRTH_YEAR = 1900
    }
}

internal object BirthProfileCodec {
    private const val VERSION = 1
    private const val SOLAR = 1
    private const val LUNAR = 2

    fun encode(profile: BirthProfile): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                when (profile) {
                    is BirthProfile.Solar -> {
                        output.writeByte(SOLAR)
                        output.writeLong(profile.gregorianDate.toEpochDay())
                    }

                    is BirthProfile.Lunar -> {
                        output.writeByte(LUNAR)
                        output.writeInt(profile.year)
                        output.writeInt(profile.month)
                        output.writeInt(profile.day)
                        output.writeBoolean(profile.leapMonth)
                        output.writeLong(profile.gregorianDate.toEpochDay())
                    }
                }
            }
            bytes.toByteArray()
        }

    fun decode(encoded: ByteArray): BirthProfile =
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported birth profile version" }
            val profile = when (input.readUnsignedByte()) {
                SOLAR -> BirthProfile.Solar(LocalDate.ofEpochDay(input.readLong()))
                LUNAR -> BirthProfile.Lunar(
                    year = input.readInt(),
                    month = input.readInt(),
                    day = input.readInt(),
                    leapMonth = input.readBoolean(),
                    gregorianDate = LocalDate.ofEpochDay(input.readLong()),
                )

                else -> throw IllegalArgumentException("Unknown birth calendar")
            }
            require(input.available() == 0) { "Unexpected birth profile data" }
            profile
        }
}
