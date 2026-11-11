package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.model.Role
import org.junit.jupiter.api.Test

/** F9/N6: learner PIN and staff password rules, plus phone canonicalisation. */
class CredentialRulesTest {

    @Test
    fun `a learner credential is exactly four digits and not trivial`() {
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "4826") == null)
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "482") != null)
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "48261") != null)
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "abcd") != null)
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "0000") != null)
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "1111") != null)
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "1234") != null)
        check(CredentialRules.validate(CredentialRules.KIND_PIN, "1357") == null)
    }

    @Test
    fun `staff keep the eight character password rule`() {
        check(CredentialRules.validate(CredentialRules.KIND_PASSWORD, "password123") == null)
        check(CredentialRules.validate(CredentialRules.KIND_PASSWORD, "short") != null)
        check(CredentialRules.validate(CredentialRules.KIND_PASSWORD, "4826") != null)
    }

    @Test
    fun `an omitted kind stays legacy PASSWORD so old clients keep working`() {
        // Only a client that explicitly names PIN opts into the 4-digit rule.
        check(CredentialRules.kindOr(null, Role.STUDENT) == CredentialRules.KIND_PASSWORD)
        check(CredentialRules.kindOr("nonsense", Role.STUDENT) == CredentialRules.KIND_PASSWORD)
        check(CredentialRules.kindOr("pin", Role.TEACHER) == CredentialRules.KIND_PIN)
    }

    @Test
    fun `phone numbers normalise to the local ten digit form`() {
        check(PhoneNumbers.normalize("0712345678") == "0712345678")
        check(PhoneNumbers.normalize("+254712345678") == "0712345678")
        check(PhoneNumbers.normalize("254712345678") == "0712345678")
        check(PhoneNumbers.normalize("712345678") == "0712345678")
        check(PhoneNumbers.normalize("0112345678") == "0112345678")
        check(PhoneNumbers.normalize("0612345678") == null)
        check(PhoneNumbers.normalize("not a phone") == null)
    }
}
