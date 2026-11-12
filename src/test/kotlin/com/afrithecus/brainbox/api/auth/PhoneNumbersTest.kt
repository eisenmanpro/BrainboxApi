package com.afrithecus.brainbox.api.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Stored canonical form plus the two gateway forms (phone verification, docs/phone_verification.md). */
class PhoneNumbersTest {

    @Test
    fun `normalises every accepted Kenyan shape to the stored canonical form`() {
        listOf("0712345678", "712345678", "254712345678", "+254712345678", " 0712 345 678 ").forEach {
            assertEquals("0712345678", PhoneNumbers.normalize(it), "shape: " + it)
        }
        assertEquals("0112345678", PhoneNumbers.normalize("+254112345678"))
        assertNull(PhoneNumbers.normalize("0812345678"))
        assertNull(PhoneNumbers.normalize("12345"))
    }

    @Test
    fun `exposes the E164 and international digit forms the gateways use`() {
        assertEquals("+254712345678", PhoneNumbers.toE164("0712345678"))
        assertEquals("254712345678", PhoneNumbers.toInternationalDigits("+254712345678"))
        assertEquals("254112887706", PhoneNumbers.toInternationalDigits("0112887706"))
        assertNull(PhoneNumbers.toE164("not a phone"))
    }
}
