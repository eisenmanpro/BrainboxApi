package com.afrithecus.brainbox.api.teacher.web

/**
 * GET teacher/dashboard payload (docs/ongoing/api_teacher_roster_changes.md).
 * Field names mirror the client TeacherDashboardData and its nested models; the
 * four work-queue counts drive the dashboard badges.
 */
data class TeacherDashboardPayload(
    val classes: List<TeacherClassView> = emptyList(),
    val pendingGradingCount: Int = 0,
    val todayClasses: List<TeacherClassView> = emptyList(),
    val upcomingDeadlines: List<TeacherHomeworkView> = emptyList(),
    val urgentQueue: List<TeacherSubmissionView> = emptyList(),
    val classPerformance: List<ClassPerformanceView> = emptyList(),
    val unreadMessagesCount: Int = 0,
    val pendingApprovalsCount: Int = 0,
    val pendingFinalizationsCount: Int = 0,
    val pendingEditRequestsCount: Int = 0,
    val conferenceRequestsCount: Int = 0,
    val announcements: List<TeacherAnnouncementPreviewView> = emptyList(),
    val upcomingItems: List<TeacherUpcomingItemView> = emptyList(),
)

data class TeacherClassView(
    val id: String,
    val name: String,
    val grade: Int,
    val section: String? = null,
    val subject: String,
    val teacherId: String,
    val teacherName: String,
    val schoolId: String,
    val studentCount: Int = 0,
    val createdAt: Long,
    val isActive: Boolean = true,
)

data class TeacherHomeworkView(
    val id: String,
    val classId: String,
    val teacherId: String,
    val schoolId: String = "",
    val teacherName: String,
    val title: String,
    val description: String,
    val subject: String,
    val gradeLevel: Int,
    val dueDate: Long,
    val submissionType: String,
    val assignedStudentIds: List<String> = emptyList(),
    val createdAt: Long,
    val isActive: Boolean = true,
    val gradingMode: String = "AUTO_IMMEDIATE",
    val scope: String = "SCHOOL_GRADE_CLASS",
    val isDraft: Boolean = false,
    val checklistItems: List<String> = emptyList(),
)

data class TeacherSubmissionView(
    val id: String,
    val homeworkId: String,
    val studentId: String,
    val studentName: String,
    val submissionText: String? = null,
    val attachmentUrl: String? = null,
    val submittedAt: Long,
    val grade: Int? = null,
    val feedback: String? = null,
    val isGraded: Boolean = false,
    val cbcStrandTag: String? = null,
    val status: String = "SUBMITTED",
)

data class ClassPerformanceView(
    val classId: String,
    val className: String,
    val subject: String,
    val averageScore: Double,
    val strandBreakdown: Map<String, Double> = emptyMap(),
)

data class TeacherAnnouncementPreviewView(
    val title: String,
    val detail: String,
    val time: String,
)

data class TeacherUpcomingItemView(
    val title: String,
    val detail: String,
    val type: String,
)
