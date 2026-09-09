package com.afrithecus.brainbox.api.classes.web

import com.afrithecus.brainbox.api.classes.ClassService
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Teacher class + roster endpoints (doc 04 §2.2), teacher-gated with ownership checks. */
@RestController
@RequestMapping("/teacher/classes")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class TeacherClassesController(
    private val service: ClassService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(@AuthenticationPrincipal currentUser: CurrentUser): List<TeacherClassPayload> =
        service.teacherClasses(teacher(currentUser))

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CreateClassRequest,
    ): TeacherClassPayload = service.createClass(teacher(currentUser), request)

    @GetMapping("/{classId}/students")
    fun students(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): List<StudentInClassPayload> = service.classStudents(teacher(currentUser), classId)

    @PostMapping("/{classId}/students")
    fun addStudents(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @Valid @RequestBody request: AddStudentsRequest,
    ): ResponseEntity<Void> {
        service.addStudents(teacher(currentUser), classId, request)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @DeleteMapping("/{classId}/students/{studentId}")
    fun removeStudent(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @PathVariable studentId: String,
    ): ResponseEntity<Void> {
        service.removeStudent(teacher(currentUser), classId, studentId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
