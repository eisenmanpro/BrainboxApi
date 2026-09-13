package com.afrithecus.brainbox.api.teacher.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** Teacher app settings (doc 04 teacher settings). */
@Entity
@Table(name = "teacher_settings")
class TeacherSettingsEntity : BaseEntity() {

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    /** JSON array string of subjects taught. */
    @Column(name = "subjects_taught", columnDefinition = "text")
    var subjectsTaught: String? = null

    @Column(name = "tsc_number", length = 64)
    var tscNumber: String? = null

    @Column(name = "default_grade_level")
    var defaultGradeLevel: Int? = null

    @Column(name = "language_preference", nullable = false, length = 16)
    var languagePreference: String = "en"

    @Column(name = "theme_preference", nullable = false, length = 16)
    var themePreference: String = "dark"

    @Column(name = "auto_attendance", nullable = false)
    var autoAttendance: Boolean = true

    @Column(name = "notifications_enabled", nullable = false)
    var notificationsEnabled: Boolean = true

    /** JSON object string of assessment-type -> weight. */
    @Column(name = "default_grade_weighting", columnDefinition = "text")
    var defaultGradeWeighting: String? = null

    @Column(name = "assignment_submission_alerts", nullable = false)
    var assignmentSubmissionAlerts: Boolean = true

    @Column(name = "new_exam_publish", nullable = false)
    var newExamPublish: Boolean = true

    @Column(name = "parent_messages", nullable = false)
    var parentMessages: Boolean = true

    @Column(name = "staff_bulletin", nullable = false)
    var staffBulletin: Boolean = true

    @Column(name = "email_notifications", nullable = false)
    var emailNotifications: Boolean = false
}
