package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.learning.model.LearningScope
import java.util.UUID

/** Content-scope rules shared by posts and readable files (doc 03 §1.2). */
object ContentScope {

    fun isVisible(
        scope: LearningScope,
        schoolId: UUID?,
        gradeLevel: String?,
        teacherId: UUID?,
        user: UserEntity,
    ): Boolean = when (scope) {
        LearningScope.GLOBAL -> true
        LearningScope.SCHOOL ->
            sameSchool(schoolId, user) && gradeMatches(gradeLevel, user.gradeLevel)
        LearningScope.SCHOOL_GRADE_CLASS ->
            sameSchool(schoolId, user) && gradeMatches(gradeLevel, user.gradeLevel) &&
                (teacherId == null || teacherId == user.joinedTeacherId)
    }

    private fun sameSchool(schoolId: UUID?, user: UserEntity): Boolean =
        user.schoolId != null && schoolId != null && schoolId == user.schoolId

    private fun gradeMatches(required: String?, userGrade: String?): Boolean =
        required == null || GradeNormalizer.sameGrade(required, userGrade)
}
