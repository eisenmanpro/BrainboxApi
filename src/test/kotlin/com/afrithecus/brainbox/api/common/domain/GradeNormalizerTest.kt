package com.afrithecus.brainbox.api.common.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradeNormalizerTest {

    @Test
    fun `form and grade spellings collapse to one canonical grade`() {
        assertEquals(GradeNormalizer.canonicalKey("Form 3"), GradeNormalizer.canonicalKey("FORM_THREE"))
        assertEquals(GradeNormalizer.canonicalKey("Form 3"), GradeNormalizer.canonicalKey("Grade 08"))
        assertTrue(GradeNormalizer.sameGrade("Form 3", "Grade 08"))
        assertTrue(GradeNormalizer.sameGrade("grade-07", "GRADE 7"))
    }

    @Test
    fun `different grades stay different`() {
        assertTrue(!GradeNormalizer.sameGrade("Form 2", "Form 3"))
        assertTrue(!GradeNormalizer.sameGrade("Grade 7", "Grade 9"))
    }

    @Test
    fun `unparseable inputs are null`() {
        assertNull(GradeNormalizer.canonicalKey(null))
        assertNull(GradeNormalizer.canonicalKey(""))
        assertNull(GradeNormalizer.canonicalKey("College"))
    }
}
