package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The one definition of "learners in a class".
 *
 * A class holds learners two ways: full accounts through `class_memberships`, and
 * roster-only (provisioned) learners through `users.provisioned_class_id` — they have no
 * login and deliberately no membership row. Counting only memberships made every class
 * list answer "0 learners" for a class whose roster showed 40: the admin School Config
 * "Classes & grades" tab disagreed with the admin Learners tab, and the teacher's class
 * list disagreed with their own learner roster.
 *
 * Counting both populations here makes every `studentCount` on a class mean the same
 * thing: the learners the class actually has. A learner upgraded to a full account keeps
 * a stale `provisioned_class_id`, so only [AccountKind.ROSTER_ONLY] rows are counted
 * there (matching [com.afrithecus.brainbox.api.identity.AdminLearnerService]).
 */
@Service
class ClassLearnerCountService(
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
) {

    fun count(classId: UUID): Int =
        membershipRepository.countByClassId(classId).toInt() +
            userRepository.countByProvisionedClassIdAndAccountKind(classId, AccountKind.ROSTER_ONLY).toInt()

    /**
     * Batch form for class lists: two queries for the whole page rather than two per row.
     * Every requested class id is present in the result (zero when the class has no learners).
     */
    fun counts(classIds: Collection<UUID>): Map<UUID, Int> {
        val unique = classIds.toSet()
        if (unique.isEmpty()) return emptyMap()
        val result = unique.associateWith { 0 }.toMutableMap()
        membershipRepository.findAllByClassIdIn(unique).forEach { membership ->
            if (result.containsKey(membership.classId)) {
                result[membership.classId] = (result[membership.classId] ?: 0) + 1
            }
        }
        userRepository.findAllByProvisionedClassIdInAndAccountKind(unique, AccountKind.ROSTER_ONLY)
            .forEach { learner ->
                val classId = learner.provisionedClassId ?: return@forEach
                if (result.containsKey(classId)) result[classId] = (result[classId] ?: 0) + 1
            }
        return result
    }
}
