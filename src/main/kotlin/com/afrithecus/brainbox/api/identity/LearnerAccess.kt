package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind

/**
 * A provisioned learner (account_kind = ROSTER_ONLY) exists only for traditional
 * exams and the reports and analysis derived from them. It is deliberately not a
 * class member, which keeps it out of attendance, gradebook, homework, CBC
 * analytics and messaging by construction; this guard is the second line, so a
 * feature that accepts a learner id rejects it even when a list filter is
 * bypassed or a client sends the id directly.
 */
object LearnerAccess {

    fun requireFull(student: UserEntity, feature: String) {
        if (student.accountKind == AccountKind.ROSTER_ONLY) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "This learner has no smartphone and is enrolled for traditional exams only; " + feature + " is not available",
            )
        }
    }
}
