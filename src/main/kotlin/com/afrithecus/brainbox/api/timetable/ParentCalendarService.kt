package com.afrithecus.brainbox.api.timetable

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.web.CalendarEventPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A linked child's calendar (docs/ongoing/api_timetable_changes.md): the child's
 * weekly classes materialised as ACADEMIC calendar events for the parent.
 */
@Service
class ParentCalendarService(
    private val learnerTimetableService: LearnerTimetableService,
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun calendar(current: CurrentUser, childIdRaw: String): List<CalendarEventPayload> {
        val child = linkedChild(current, childIdRaw)
        return learnerTimetableService.timetable(child).map { slot ->
            val title = if (slot.teacherName.isBlank()) {
                slot.subject
            } else {
                slot.subject + " with " + slot.teacherName
            }
            CalendarEventPayload(
                id = "tt_" + slot.id,
                title = title,
                date = slot.startMillis,
                type = "ACADEMIC",
                childId = child.id.toString(),
                location = slot.room,
            )
        }.sortedBy { it.date }
    }

    private fun linkedChild(current: CurrentUser, childIdRaw: String): UserEntity {
        val id = runCatching { UUID.fromString(childIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("childId is not a valid identifier")
        val child = userRepository.findById(id).orElse(null) ?: throw notFound("Child not found")
        if (current.role != Role.ADMIN && child.parentUserId != current.userId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your linked child")
        }
        return child
    }
}
