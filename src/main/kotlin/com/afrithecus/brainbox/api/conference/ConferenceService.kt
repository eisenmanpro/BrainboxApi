package com.afrithecus.brainbox.api.conference

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.conference.entity.ConferenceBookingEntity
import com.afrithecus.brainbox.api.conference.entity.ConferenceSlotEntity
import com.afrithecus.brainbox.api.conference.repository.ConferenceBookingRepository
import com.afrithecus.brainbox.api.conference.repository.ConferenceSlotRepository
import com.afrithecus.brainbox.api.conference.web.ConferenceBookingPayload
import com.afrithecus.brainbox.api.conference.web.ConferenceSlotPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Teacher/parent conferences (doc 04 section 15, docs/ongoing/api_conference_changes.md).
 * Slots and bookings are client-id idempotent because both sides replay offline
 * writes; slot status is recomputed from the confirmed booking count.
 */
@Service
class ConferenceService(
    private val slotRepository: ConferenceSlotRepository,
    private val bookingRepository: ConferenceBookingRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val classRepository: TeacherClassRepository,
    private val notificationService: NotificationService,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZone: String,
) {

    // ------------------------------------------------------------ teacher

    @Transactional(readOnly = true)
    fun teacherSlots(teacher: UserEntity): List<ConferenceSlotPayload> {
        requireTeacher(teacher)
        return slotRepository.findAllByTeacherIdOrderBySlotDateAsc(teacher.id).map { slotPayload(it) }
    }

    /** Upserts per client id and returns the canonical slot (the client reconciles). */
    @Transactional
    fun createSlot(teacher: UserEntity, request: ConferenceSlotPayload): ConferenceSlotPayload {
        requireTeacher(teacher)
        val rawId = request.id.trim()
        val clientId = rawId.takeIf { it.isNotEmpty() } ?: "slot_" + UUID.randomUUID()
        val existing = slotRepository.findByClientId(clientId)
            ?: rawId.takeIf { isUuid(it) }?.let { slotRepository.findById(UUID.fromString(it)).orElse(null) }
        if (existing != null && existing.teacherId != teacher.id) throw forbidden("Not your slot")
        val entity = existing ?: ConferenceSlotEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        applySlot(entity, request, teacher)
        slotRepository.saveAndFlush(entity)
        if (existing == null && entity.isRecurring && !entity.recurrenceRule.isNullOrBlank()) {
            expandSeries(entity, clientId)
        }
        return slotPayload(entity)
    }

    @Transactional
    fun updateSlot(teacher: UserEntity, request: ConferenceSlotPayload): ConferenceSlotPayload {
        requireTeacher(teacher)
        val entity = resolveSlot(teacher, request.id) ?: throw notFound("Slot not found")
        applySlot(entity, request, teacher)
        slotRepository.saveAndFlush(entity)
        return slotPayload(entity)
    }

    /** Repeat-safe: an unknown slot is treated as already deleted. */
    @Transactional
    fun deleteSlot(teacher: UserEntity, slotIdRaw: String) {
        requireTeacher(teacher)
        val entity = resolveSlot(teacher, slotIdRaw) ?: return
        slotRepository.delete(entity)
    }

    @Transactional(readOnly = true)
    fun bookingsForSlot(teacher: UserEntity, slotIdRaw: String): List<ConferenceBookingPayload> {
        requireTeacher(teacher)
        val slot = requireOwnedSlot(teacher, slotIdRaw)
        // Pending requests surface first so the teacher sees the decision queue.
        val rows = bookingRepository.findAllBySlotIdOrderByCreatedAtAsc(slot.id)
            .sortedWith(compareBy({ if (it.status == "PENDING") 0 else 1 }, { it.createdAt }))
        return bookingPayloads(rows)
    }

    @Transactional
    fun updateBookingStatus(teacher: UserEntity, bookingIdRaw: String, statusRaw: String): ConferenceBookingPayload {
        requireTeacher(teacher)
        val status = statusRaw.trim().uppercase()
        if (status !in DECIDABLE_BOOKING_STATUSES) throw invalidArgument("Unknown booking status: " + statusRaw)
        val booking = resolveBooking(bookingIdRaw) ?: throw notFound("Booking not found")
        val slot = slotRepository.findById(booking.slotId).orElse(null) ?: throw notFound("Slot not found")
        if (slot.teacherId != teacher.id) throw forbidden("Not your booking")
        if (booking.status == status) return bookingPayload(booking)
        booking.status = status
        if (status == "CONFIRMED") {
            booking.confirmedAt = clock.instant()
            booking.confirmedBy = teacher.id
        }
        bookingRepository.saveAndFlush(booking)
        return bookingPayload(booking)
    }

    @Transactional
    fun sendReminder(teacher: UserEntity, bookingIdRaw: String, reminderType: String) {
        requireTeacher(teacher)
        val booking = resolveBooking(bookingIdRaw) ?: throw notFound("Booking not found")
        val slot = slotRepository.findById(booking.slotId).orElse(null) ?: throw notFound("Slot not found")
        if (slot.teacherId != teacher.id) throw forbidden("Not your booking")
        val method = reminderType.trim().trim('"').uppercase().ifEmpty { "EMAIL" }
        val last = booking.reminderSentAt
        if (last != null && Duration.between(last, clock.instant()).toHours() < REMINDER_WINDOW_HOURS) return
        val parent = userRepository.findById(booking.parentId).orElse(null)
        val child = userRepository.findById(booking.childId).orElse(null)
        if (parent != null) {
            notificationService.notifyUser(
                userId = parent.id,
                title = "Conference reminder",
                message = "Reminder about your " + (slot.title) + " meeting" +
                    (child?.let { " for " + it.name } ?: "") + " on " + slot.startTime + ".",
                type = NotificationType.SYSTEM,
                actionRoute = "conferences",
                actionLabel = "View",
                metadata = mapOf("bookingId" to booking.id.toString(), "method" to method),
            )
        }
        booking.reminderSentAt = clock.instant()
        bookingRepository.saveAndFlush(booking)
    }

    /** Stable per slot: generated once and reused. */
    @Transactional
    fun meetLink(teacher: UserEntity, slotIdRaw: String): String {
        requireTeacher(teacher)
        val slot = requireOwnedSlot(teacher, slotIdRaw)
        val existing = slot.meetLink
        if (!existing.isNullOrBlank()) return existing
        val link = "https://meet.brainbox.com/s/" + slot.id
        slot.meetLink = link
        slotRepository.saveAndFlush(slot)
        return link
    }

    // ------------------------------------------------------------ parent

    @Transactional(readOnly = true)
    fun parentSlots(parent: UserEntity): List<ConferenceSlotPayload> {
        val children = userRepository.findByParentUserId(parent.id)
        if (children.isEmpty()) return emptyList()
        val childClassIds = children.flatMap { membershipRepository.findAllByStudentId(it.id).map { m -> m.classId } }.toSet()
        if (childClassIds.isEmpty()) return emptyList()
        val teacherIds = classRepository.findAllById(childClassIds).map { it.teacherUserId }.toSet()
        if (teacherIds.isEmpty()) return emptyList()
        return slotRepository.findAllByTeacherIdInOrderBySlotDateAsc(teacherIds)
            .filter { it.status != "CANCELLED" }
            .filter { parentAudienceVisible(it) }
            .map { slot ->
                val booked = children.any { child ->
                    ACTIVE_BOOKING_STATUSES.any {
                        bookingRepository.findBySlotIdAndChildIdAndStatus(slot.id, child.id, it) != null
                    }
                }
                slotPayload(slot, isBooked = booked, recomputeStatus = true)
            }
    }

    /** Idempotent per booking id. */
    @Transactional
    fun book(parent: UserEntity, request: ConferenceBookingPayload): ConferenceBookingPayload {
        val slot = resolveSlotRaw(request.slotId) ?: throw notFound("Slot not found")
        if (slot.status == "CANCELLED" || slot.status == "COMPLETED") throw conflict("Slot is not open for booking")
        val child = userRepository.findById(parseUuid(request.childId, "childId")).orElse(null)
            ?: throw notFound("Learner not found")
        if (child.parentUserId != parent.id) throw forbidden("Not your child")
        val rawId = request.id.trim()
        val clientId = rawId.takeIf { it.isNotEmpty() } ?: "b_" + UUID.randomUUID()
        val existing = bookingRepository.findByClientId(clientId)
            ?: rawId.takeIf { isUuid(it) }?.let { bookingRepository.findById(UUID.fromString(it)).orElse(null) }
        if (existing != null && existing.parentId != parent.id) throw forbidden("Not your booking")
        // A replay of an offline write never resets a decided request.
        if (existing != null) return bookingPayload(existing)
        val clash = ACTIVE_BOOKING_STATUSES.firstNotNullOfOrNull {
            bookingRepository.findBySlotIdAndChildIdAndStatus(slot.id, child.id, it)
        }
        if (clash != null) throw conflict("This learner already has this slot booked")
        if (slotIsFull(slot)) throw conflict("Slot is full")
        val entity = ConferenceBookingEntity().apply {
            this.clientId = clientId
            this.slotId = slot.id
            parentId = parent.id
            childId = child.id
            teacherName = slot.teacherName ?: userRepository.findById(slot.teacherId).orElse(null)?.name
            bookingDate = slot.slotDate
            bookingTime = slot.startTime
            meetLink = slot.meetLink
            notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
            status = "PENDING"
            requestedAt = clock.instant()
        }
        bookingRepository.saveAndFlush(entity)
        notifyTeacher(slot, child, parent)
        return bookingPayload(entity)
    }

    /**
     * Expiry job: a PENDING request lapses 48 h after it was made, or 24 h before
     * the slot starts, whichever is sooner; the soft hold is released.
     */
    @Transactional
    fun expireStaleRequests() {
        val now = clock.instant()
        bookingRepository.findAllByStatusOrderByRequestedAtAsc("PENDING").forEach { booking ->
            val slot = slotRepository.findById(booking.slotId).orElse(null) ?: return@forEach
            val requestDeadline = booking.requestedAt.plus(Duration.ofHours(PENDING_MAX_HOURS))
            val leadDeadline = slotStart(slot).minus(Duration.ofHours(PENDING_LEAD_HOURS))
            val expiresAt = if (leadDeadline.isBefore(requestDeadline)) leadDeadline else requestDeadline
            if (!now.isBefore(expiresAt)) {
                booking.status = "EXPIRED"
                bookingRepository.save(booking)
            }
        }
    }

    /** Repeat-safe cancel; the slot becomes bookable again. */
    @Transactional
    fun cancelBooking(parent: UserEntity, bookingIdRaw: String) {
        val booking = resolveBooking(bookingIdRaw) ?: return
        if (booking.parentId != parent.id) throw forbidden("Not your booking")
        bookingRepository.delete(booking)
    }

    @Transactional(readOnly = true)
    fun parentBookings(parent: UserEntity): List<ConferenceBookingPayload> =
        bookingPayloads(bookingRepository.findAllByParentIdOrderByBookingDateDesc(parent.id))

    // ------------------------------------------------------------ internals

    private fun applySlot(entity: ConferenceSlotEntity, request: ConferenceSlotPayload, teacher: UserEntity) {
        val title = request.title.trim()
        if (title.isEmpty()) throw invalidArgument("Slot title is required")
        val start = request.startTime.trim()
        val end = request.endTime.trim()
        if (start.isEmpty() || end.isEmpty()) throw invalidArgument("Slot startTime and endTime are required")
        entity.teacherId = teacher.id
        entity.teacherName = teacher.name
        entity.title = title
        entity.slotDate = if (request.date > 0) Instant.ofEpochMilli(request.date) else clock.instant()
        entity.startTime = start
        entity.endTime = end
        entity.durationMinutes = (request.durationMinutes.takeIf { it > 0 } ?: request.duration).coerceAtLeast(1)
        entity.maxBookings = request.maxBookings.coerceAtLeast(1)
        entity.isVirtual = request.isVirtual
        entity.location = request.location?.trim()?.takeIf { it.isNotEmpty() }
        entity.isRecurring = request.isRecurring
        entity.recurrenceRule = request.recurrenceRule?.trim()?.takeIf { it.isNotEmpty() }
        entity.status = validateSlotStatus(request.status)
        entity.cancellationReason = request.cancellationReason?.trim()?.takeIf { it.isNotEmpty() }
        entity.audienceTarget = validateAudience(request.audienceTarget)
        entity.createdByRole = request.createdByRole.trim().ifEmpty { "TEACHER" }
        entity.linkedLiveClassId = request.linkedLiveClassId?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Materialises a recurring slot's occurrences as concrete sibling slots. The
     * ids are deterministic (`<baseClientId>_r<n>`) so an offline replay upserts
     * instead of duplicating the series (CONF-1).
     */
    private fun expandSeries(base: ConferenceSlotEntity, baseClientId: String) {
        val zone = runCatching { ZoneId.of(schoolZone) }.getOrDefault(ZoneId.of("Africa/Nairobi"))
        val occurrences = RecurrenceExpander.expand(base.slotDate, base.recurrenceRule, zone).occurrences.drop(1)
        occurrences.forEachIndexed { index, instant ->
            val siblingId = baseClientId + "_r" + (index + 1)
            if (slotRepository.findByClientId(siblingId) != null) return@forEachIndexed
            slotRepository.save(ConferenceSlotEntity().apply {
                this.clientId = siblingId
                teacherId = base.teacherId
                teacherName = base.teacherName
                title = base.title
                slotDate = instant
                startTime = base.startTime
                endTime = base.endTime
                durationMinutes = base.durationMinutes
                maxBookings = base.maxBookings
                isVirtual = base.isVirtual
                location = base.location
                status = "OPEN"
                audienceTarget = base.audienceTarget
                createdByRole = base.createdByRole
                isRecurring = false
                recurrenceRule = null
            })
        }
    }

    private fun parentAudienceVisible(slot: ConferenceSlotEntity): Boolean = when (slot.audienceTarget) {
        "GRADE_PARENTS", "WHOLE_SCHOOL_PARENTS", "WHOLE_SCHOOL" -> true
        else -> false
    }

    private fun slotPayload(
        slot: ConferenceSlotEntity,
        isBooked: Boolean = false,
        recomputeStatus: Boolean = false,
    ): ConferenceSlotPayload {
        val effectiveStatus = when {
            !recomputeStatus -> slot.status
            slot.status == "CANCELLED" || slot.status == "COMPLETED" -> slot.status
            slotIsFull(slot) -> "FULL"
            else -> "OPEN"
        }
        return ConferenceSlotPayload(
            id = slot.id.toString(),
            teacherId = slot.teacherId.toString(),
            teacherName = slot.teacherName ?: userRepository.findById(slot.teacherId).orElse(null)?.name.orEmpty(),
            title = slot.title,
            date = slot.slotDate.toEpochMilli(),
            startTime = slot.startTime,
            endTime = slot.endTime,
            time = slot.startTime,
            durationMinutes = slot.durationMinutes,
            duration = slot.durationMinutes,
            maxBookings = slot.maxBookings,
            meetLink = slot.meetLink,
            isVirtual = slot.isVirtual,
            location = slot.location,
            isRecurring = slot.isRecurring,
            recurrenceRule = slot.recurrenceRule,
            status = effectiveStatus,
            cancellationReason = slot.cancellationReason,
            audienceTarget = slot.audienceTarget,
            createdByRole = slot.createdByRole,
            linkedLiveClassId = slot.linkedLiveClassId,
            createdAt = slot.createdAt.toEpochMilli(),
            isBooked = isBooked,
        )
    }

    private fun bookingPayloads(bookings: List<ConferenceBookingEntity>): List<ConferenceBookingPayload> {
        val users = userRepository.findAllById(
            bookings.flatMap { listOf(it.parentId, it.childId) }.toSet(),
        ).associateBy { it.id }
        return bookings.map { booking -> bookingPayload(booking, users) }
    }

    private fun bookingPayload(
        booking: ConferenceBookingEntity,
        users: Map<UUID, UserEntity> = userRepository.findAllById(listOf(booking.parentId, booking.childId)).associateBy { it.id },
    ): ConferenceBookingPayload {
        val parent = users[booking.parentId]
        val child = users[booking.childId]
        return ConferenceBookingPayload(
            id = booking.clientId,
            slotId = booking.slotId.toString(),
            parentId = booking.parentId.toString(),
            parentName = parent?.name.orEmpty(),
            childId = booking.childId.toString(),
            childName = child?.name.orEmpty(),
            childGrade = child?.gradeLevel.orEmpty(),
            teacherName = booking.teacherName.orEmpty(),
            date = booking.bookingDate.toEpochMilli(),
            time = booking.bookingTime.orEmpty(),
            meetLink = booking.meetLink,
            notes = booking.notes,
            status = booking.status,
            requestedAt = booking.requestedAt.toEpochMilli(),
            confirmedAt = booking.confirmedAt?.toEpochMilli(),
        )
    }

    /**
     * A PENDING request soft-holds one seat for a single-seat slot so the teacher
     * can review without a double-booking; for larger slots only confirmed
     * bookings consume capacity.
     */
    private fun slotIsFull(slot: ConferenceSlotEntity): Boolean {
        if (bookingRepository.countBySlotIdAndStatus(slot.id, "CONFIRMED") >= slot.maxBookings) return true
        return slot.maxBookings == 1 && bookingRepository.countBySlotIdAndStatus(slot.id, "PENDING") > 0
    }

    private fun notifyTeacher(slot: ConferenceSlotEntity, child: UserEntity, parent: UserEntity) {
        notificationService.notifyUser(
            userId = slot.teacherId,
            title = "New conference booking",
            message = parent.name + " booked " + slot.title + " for " + child.name + " at " + slot.startTime + ".",
            type = NotificationType.SYSTEM,
            actionRoute = "conferences",
            actionLabel = "View",
            metadata = mapOf("slotId" to slot.id.toString()),
        )
    }

    private fun resolveSlot(teacher: UserEntity, raw: String): ConferenceSlotEntity? {
        val entity = resolveSlotRaw(raw) ?: return null
        if (entity.teacherId != teacher.id) throw forbidden("Not your slot")
        return entity
    }

    private fun resolveSlotRaw(raw: String): ConferenceSlotEntity? {
        if (raw.isBlank()) return null
        slotRepository.findByClientId(raw)?.let { return it }
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull() ?: return null
        return slotRepository.findById(id).orElse(null)
    }

    private fun requireOwnedSlot(teacher: UserEntity, raw: String): ConferenceSlotEntity =
        resolveSlot(teacher, raw) ?: throw notFound("Slot not found")

    private fun resolveBooking(raw: String): ConferenceBookingEntity? {
        if (raw.isBlank()) return null
        bookingRepository.findByClientId(raw)?.let { return it }
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull() ?: return null
        return bookingRepository.findById(id).orElse(null)
    }

    private fun validateSlotStatus(raw: String): String {
        val value = raw.trim().uppercase().ifEmpty { "OPEN" }
        if (value !in SLOT_STATUSES) throw invalidArgument("Unknown slot status: " + raw)
        return value
    }

    private fun validateAudience(raw: String): String {
        val value = raw.trim().uppercase().ifEmpty { "WHOLE_SCHOOL" }
        if (value !in AUDIENCES) throw invalidArgument("Unknown audience target: " + raw)
        return value
    }

    private fun slotStart(slot: ConferenceSlotEntity): Instant {
        val zone = runCatching { ZoneId.of(schoolZone) }.getOrDefault(ZoneId.of("Africa/Nairobi"))
        val date = slot.slotDate.atZone(zone).toLocalDate()
        val time = runCatching { LocalTime.parse(slot.startTime) }.getOrDefault(LocalTime.MIDNIGHT)
        return date.atTime(time).atZone(zone).toInstant()
    }

    private fun isUuid(raw: String): Boolean = runCatching { UUID.fromString(raw.trim()) }.isSuccess

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw forbidden("Teacher access only")
    }

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)

    private companion object {
        const val REMINDER_WINDOW_HOURS = 1L
        const val PENDING_MAX_HOURS = 48L
        const val PENDING_LEAD_HOURS = 24L
        val SLOT_STATUSES = setOf("OPEN", "FULL", "CANCELLED", "COMPLETED")
        val DECIDABLE_BOOKING_STATUSES = setOf("CONFIRMED", "CANCELLED", "ATTENDED", "EXPIRED")
        val ACTIVE_BOOKING_STATUSES = listOf("PENDING", "CONFIRMED")
        val AUDIENCES = setOf(
            "GRADE_PARENTS", "GRADE_STUDENTS", "GRADE_TEACHERS",
            "WHOLE_SCHOOL_STUDENTS", "WHOLE_SCHOOL_PARENTS", "WHOLE_SCHOOL",
        )
    }
}
