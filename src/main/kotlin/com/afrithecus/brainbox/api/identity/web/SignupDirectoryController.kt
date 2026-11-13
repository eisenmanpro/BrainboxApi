package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.SignupDirectoryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public signup directory (gaps.md item 1C): the school list and each school's grades/classes,
 * readable before an account exists so the signup form can present dropdowns.
 */
@RestController
@RequestMapping("/auth/signup")
class SignupDirectoryController(private val service: SignupDirectoryService) {

    @GetMapping("/schools")
    fun schools(): List<SignupSchoolPayload> = service.schools()

    @GetMapping("/schools/{schoolId}")
    fun school(@PathVariable schoolId: String): SignupSchoolDetailPayload = service.school(schoolId)
}
