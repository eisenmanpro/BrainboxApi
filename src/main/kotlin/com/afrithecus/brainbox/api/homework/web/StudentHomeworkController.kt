package com.afrithecus.brainbox.api.homework.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.homework.StudentHomeworkService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.media.MediaService
import com.afrithecus.brainbox.api.media.MediaUploadResponsePayload
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Student homework surface. */
@RestController
@RequestMapping("/homework")
class StudentHomeworkController(
    private val service: StudentHomeworkService,
    private val userRepository: UserRepository,
    private val mediaService: MediaService,
) {
    private fun student(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(required = false) grade: String?,
        @RequestParam(required = false) classId: String?,
    ): List<StudentHomeworkPayload> =
        service.myHomework(student(currentUser), schoolId, grade, classId)

    @GetMapping("/{homeworkId}")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
    ): StudentHomeworkPayload = service.detail(student(currentUser), homeworkId)

    /** Uploads an attachment for a FREE_TEXT/OFFLINE submission; returns its hosted URL. */
    @PostMapping("/attachments")
    fun uploadAttachment(@RequestPart("file") file: MultipartFile): MediaUploadResponsePayload =
        mediaService.storeHomeworkAttachment(file)

    @PostMapping("/{homeworkId}/submit")
    fun submit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
        @Valid @RequestBody request: StudentSubmitRequest,
    ): StudentHomeworkPayload = service.submit(student(currentUser), homeworkId, request)
}
