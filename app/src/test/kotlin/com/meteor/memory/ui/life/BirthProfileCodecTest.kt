package com.meteor.memory.ui.life

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BirthProfileCodecTest {

    @Test
    fun `solar birth profile survives encode and decode`() {
        val profile = BirthProfile.Solar(LocalDate.of(2001, 2, 3))

        assertEquals(profile, BirthProfileCodec.decode(BirthProfileCodec.encode(profile)))
    }

    @Test
    fun `lunar birth profile preserves leap month and converted date`() {
        val profile = BirthProfile.Lunar(
            year = 2023,
            month = 2,
            day = 5,
            leapMonth = true,
            gregorianDate = LocalDate.of(2023, 3, 26),
        )

        assertEquals(profile, BirthProfileCodec.decode(BirthProfileCodec.encode(profile)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported profile versions are rejected`() {
        BirthProfileCodec.decode(byteArrayOf(0, 0, 0, 2, 1))
    }
}
