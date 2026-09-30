package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.web.ReportType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/ongoing/product_ops_roadmap.md item 1. */
class ReportPolicyTests {

    @Test
    fun `free and counted partition every report type`() {
        val all = ReportType.entries.toSet()
        val free = ReportPolicy.freeTypes().toSet()
        val counted = ReportPolicy.countedTypes().toSet()
        assertEquals(all, free + counted, "every report type must be classified")
        assertTrue((free intersect counted).isEmpty(), "a type cannot be both free and counted")
    }

    @Test
    fun `the three free kinds named in the brief are unlimited`() {
        listOf(
            ReportType.TRADITIONAL_GRADE_ANALYSIS,
            ReportType.TRADITIONAL_COMBINED,
            ReportType.TRADITIONAL_PER_CLASS_TABLES,
        ).forEach { assertFalse(ReportPolicy.isStudentCounted(it), "$it must be free") }
    }

    @Test
    fun `per student reports are counted`() {
        listOf(
            ReportType.CBC_STUDENT,
            ReportType.TRADITIONAL_STUDENT,
            ReportType.DETAILED_CBC_STUDENT,
        ).forEach { assertTrue(ReportPolicy.isStudentCounted(it), "$it must be counted") }
    }

    @Test
    fun `dispatch notice names the parent download path`() {
        assertTrue(ReportPolicy.DISPATCH_NOTICE.contains("parents should download from their end"))
    }
}
