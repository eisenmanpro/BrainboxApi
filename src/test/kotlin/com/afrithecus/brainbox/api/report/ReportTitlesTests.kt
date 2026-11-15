package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.web.ReportType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D6/F19: one canonical title source for jobs, renderers and template downloads. */
class ReportTitlesTests {

    @Test
    fun `every report type has a distinct non-blank title and a description`() {
        val titles = ReportType.entries.map { ReportTitles.title(it) }
        assertEquals(ReportType.entries.size, titles.distinct().size)
        assertTrue(titles.all { it.isNotBlank() })
        assertTrue(ReportType.entries.all { ReportTitles.description(it).isNotBlank() })
    }

    @Test
    fun `dynamic titles keep the exam or grade prefix`() {
        assertEquals("Grade 5 End Term 2", ReportTitles.combined("Grade 5 End Term 2"))
        assertEquals("Grade 5 End Term 2 - Per Class Tables", ReportTitles.perClass("Grade 5 End Term 2"))
        assertEquals("Grade 5 Grade Analysis", ReportTitles.analysis("Grade 5"))
    }

    @Test
    fun `template titles and orientations follow the canonical map`() {
        assertEquals("Template - Class CBC Report", ReportTitles.templateTitle(ReportType.CBC_CLASS))
        assertTrue(ReportTitles.landscape(ReportType.TRADITIONAL_COMBINED))
        assertTrue(ReportTitles.landscape(ReportType.TEACHER_PERFORMANCE))
        assertTrue(!ReportTitles.landscape(ReportType.CBC_STUDENT))
        assertTrue(!ReportTitles.landscape(ReportType.DETAILED_CBC_STUDENT))
    }
}
