package com.afrithecus.brainbox.api.console

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.console.web.ConsoleAudiencePreview
import com.afrithecus.brainbox.api.console.web.ConsoleCreatedUser
import com.afrithecus.brainbox.api.console.web.ConsoleIdentityPayload
import com.afrithecus.brainbox.api.console.web.ConsoleNotificationView
import com.afrithecus.brainbox.api.console.web.ConsoleSchoolView
import com.afrithecus.brainbox.api.console.web.ConsoleSubscriberView
import com.afrithecus.brainbox.api.console.web.ConsoleUserView
import com.afrithecus.brainbox.api.console.web.ConsoleAutomationRuleView
import com.afrithecus.brainbox.api.console.web.SubjectAgentPromptView
import com.afrithecus.brainbox.api.identity.ConsoleAccountView
import com.afrithecus.brainbox.api.identity.ConsoleRoleView
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * The platform console end to end: role-scoped identity, user and school administration,
 * subscribers, agent prompts, notifications, automation and news.
 *
 * Several of these tests are about the console's own security: `/me` must hand an operator
 * exactly the actionable items their capabilities allow, and nothing else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConsolePlatformWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val subscriptions: SubscriptionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: Clock,
) {

    @Test
    fun `me returns only the actions the caller's capabilities allow`() {
        val owner = admin("0755080001", listOf(PlatformPermission.PLATFORM_ADMIN))
        val support = admin(
            "0755080002",
            listOf(PlatformPermission.CONSOLE_READ, PlatformPermission.USERS_READ),
        )

        val ownerIdentity = identity(owner)
        check(ownerIdentity.capabilities.contains("PLATFORM_ADMIN"))
        check(ownerIdentity.actions.size == com.afrithecus.brainbox.api.identity.ConsoleActions.all().size) {
            "an owner sees the whole catalogue, saw " + ownerIdentity.actions.size
        }

        val supportIdentity = identity(support)
        check(supportIdentity.capabilities == listOf("CONSOLE_READ", "USERS_READ")) {
            supportIdentity.capabilities.toString()
        }
        check(supportIdentity.actions.isNotEmpty())
        check(supportIdentity.actions.all { it.capability == "CONSOLE_READ" || it.capability == "USERS_READ" }) {
            "a support operator must only see actions their capabilities grant"
        }
        check(supportIdentity.actions.any { it.id == "users.list" })
        check(supportIdentity.actions.none { it.id.startsWith("users.create") })
        check(supportIdentity.actions.none { it.id.startsWith("security.") })
    }

    @Test
    fun `the owner creates a role, assigns it, and the assignee sees exactly that role`() {
        val owner = admin("0755080003", listOf(PlatformPermission.PLATFORM_ADMIN))
        val ownerToken = token(owner)
        val candidate = admin("0755080004", emptyList())

        val role = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/roles").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"School Onboarding","description":"Register schools",""" +
                            """"capabilities":["CONSOLE_READ","SCHOOLS_READ","SCHOOLS_MANAGE"]}""",
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleRoleView::class.java,
        )
        check(role.capabilities == listOf("CONSOLE_READ", "SCHOOLS_MANAGE", "SCHOOLS_READ")) {
            role.capabilities.toString()
        }
        check(!role.isSystem)

        val assigned = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/roles/accounts/${candidate.id}").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"roleId":"${role.roleId}"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleAccountView::class.java,
        )
        // A role name is a stable identifier: normalised to upper case with underscores.
        check(assigned.roleName == "SCHOOL_ONBOARDING") { assigned.roleName ?: "null" }
        check(assigned.capabilities.contains("SCHOOLS_MANAGE"))

        val assigneeIdentity = identity(candidate)
        check(assigneeIdentity.consoleRoleName == "SCHOOL_ONBOARDING")
        check(assigneeIdentity.actions.any { it.id == "schools.create" })
        check(assigneeIdentity.actions.none { it.id == "users.list" })

        mockMvc.perform(
            post("/admin/console/roles/accounts/${candidate.id}").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"roleId":"${UUID.randomUUID()}"}""")
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            post("/admin/console/roles").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Nope","description":"","capabilities":["WORLD_DOMINATION"]}""")
        ).andExpect(status().isBadRequest)
        // Only PLATFORM_ADMIN may create roles; the assignee cannot widen their own authority.
        mockMvc.perform(
            post("/admin/console/roles").header("Authorization", auth(token(candidate)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Sneaky","description":"","capabilities":["PLATFORM_ADMIN"]}""")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `creation returns working credentials, suspension blocks sign-in and reset re-issues`() {
        val owner = admin("0755080005", listOf(PlatformPermission.PLATFORM_ADMIN))
        val ownerToken = token(owner)

        val learner = createUser(
            ownerToken,
            """{"name":"Console Learner","role":"STUDENT","phoneNumber":"0755080101","gradeLevel":"Grade 6","verified":true}""",
        )
        check(learner.user.role == "STUDENT")
        check(learner.admissionNumber != null)
        check(learner.credential.temporaryPassword.length >= 12)

        signIn(learner.credential.identifier, learner.credential.temporaryPassword)

        val teacher = createUser(
            ownerToken,
            """{"name":"Console Teacher","role":"TEACHER","subRole":"ICT_ADMIN","phoneNumber":"0755080102"}""",
        )
        check(teacher.user.subRole == "ICT_ADMIN")

        val students: List<ConsoleUserView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/users?role=STUDENT").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleUserView::class.java),
        )
        check(students.any { it.userId == learner.user.userId })
        check(students.all { it.role == "STUDENT" })

        mockMvc.perform(
            post("/admin/console/users/${teacher.user.userId}/active").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":false,"reason":"left the school"}""")
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "identifier" to teacher.credential.identifier,
                            "password" to teacher.credential.temporaryPassword,
                        ),
                    ),
                )
        ).andExpect(status().isUnauthorized)

        val reset = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/users/${teacher.user.userId}/password-reset")
                    .header("Authorization", auth(ownerToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.console.web.ConsoleCredential::class.java,
        )
        check(reset.temporaryPassword != teacher.credential.temporaryPassword)
        // Still suspended: a reset does not silently restore access.
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("identifier" to reset.identifier, "password" to reset.temporaryPassword),
                    ),
                )
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/admin/console/users/${teacher.user.userId}/active").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":true}""")
        ).andExpect(status().isOk)
        signIn(reset.identifier, reset.temporaryPassword)

        mockMvc.perform(
            post("/admin/console/users").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Clone","role":"STUDENT","phoneNumber":"${learner.credential.identifier}"}""")
        ).andExpect(status().isConflict)

        val support = admin(
            "0755080006",
            listOf(PlatformPermission.CONSOLE_READ, PlatformPermission.USERS_READ),
        )
        mockMvc.perform(
            post("/admin/console/users").header("Authorization", auth(token(support)))
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Nope","role":"STUDENT"}""")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `schools, subscribers, agent prompts, notifications and news work from the console`() {
        val owner = admin("0755080007", listOf(PlatformPermission.PLATFORM_ADMIN))
        val ownerToken = token(owner)

        val school = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/schools").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Console Campus","county":"Nairobi","location":"Westlands"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleSchoolView::class.java,
        )
        check(school.isActive)
        mockMvc.perform(
            post("/admin/console/schools/${school.schoolId}/active").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":false}""")
        ).andExpect(status().isOk)
        val suspended: List<ConsoleSchoolView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/schools?active=false").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleSchoolView::class.java),
        )
        check(suspended.any { it.schoolId == school.schoolId })

        val paying = account("0755080008", Role.STUDENT)
        subscriptions.save(
            SubscriptionEntity().apply {
                userId = paying.id
                status = SubscriptionStatus.ACTIVE
                expiryDate = clock.instant().plusSeconds(86_400)
            }
        )
        val lapsed = account("0755080009", Role.STUDENT)
        subscriptions.save(
            SubscriptionEntity().apply {
                userId = lapsed.id
                status = SubscriptionStatus.EXPIRED
                expiryDate = clock.instant().minusSeconds(86_400)
            }
        )
        val active: List<ConsoleSubscriberView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/subscribers?activeOnly=true").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleSubscriberView::class.java),
        )
        check(active.any { it.userId == paying.id.toString() && it.active })
        check(active.none { it.userId == lapsed.id.toString() })

        val agents: List<SubjectAgentPromptView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/agents").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, SubjectAgentPromptView::class.java),
        )
        val math = agents.first { it.agentCode == "MATH" }
        check(!math.overridden && math.persona.isNotBlank())

        val updated = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/agents/MATH").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"persona":"You are the console-edited mathematics agent.",""" +
                            """"assessmentGuidance":"Console assessment rule.","notesGuidance":"Console lesson rule."}""",
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SubjectAgentPromptView::class.java,
        )
        check(updated.overridden && updated.promptVersion == 1)
        check(updated.persona.contains("console-edited"))
        mockMvc.perform(
            delete("/admin/console/agents/MATH").header("Authorization", auth(ownerToken))
        ).andExpect(status().isOk)

        val preview = objectMapper.readValue(
            mockMvc.perform(
                get("/admin/console/notifications/audience?audienceType=ROLE&audienceValue=STUDENT")
                    .header("Authorization", auth(ownerToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleAudiencePreview::class.java,
        )
        check(preview.recipientCount >= 2)

        val sent = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/notifications").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"title":"Term dates","body":"Term two begins on Monday.",""" +
                            """"audienceType":"ROLE","audienceValue":"STUDENT"}""",
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleNotificationView::class.java,
        )
        check(sent.status == "SENT" && sent.recipientCount >= 2)

        val scheduled = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/notifications").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"title":"Later","body":"Scheduled body","audienceType":"ALL",""" +
                            """"scheduledAt":${System.currentTimeMillis() + 3_600_000}}""",
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleNotificationView::class.java,
        )
        check(scheduled.status == "SCHEDULED" && scheduled.recipientCount == 0)
        val cancelled = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/notifications/${scheduled.notificationId}/cancel")
                    .header("Authorization", auth(ownerToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleNotificationView::class.java,
        )
        check(cancelled.status == "CANCELLED")

        val rule = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/notifications/rules").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"Expiry reminder","triggerType":"SUBSCRIPTION_EXPIRING",""" +
                            """"thresholdDays":7,"title":"Your plan expires soon","body":"Renew to keep access."}""",
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleAutomationRuleView::class.java,
        )
        check(rule.enabled)
        mockMvc.perform(
            post("/admin/console/notifications/rules/${rule.ruleId}/enabled").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":false}""")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/admin/console/news").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"title":"Console announcement","content":"Published from the platform console.","status":"PUBLISHED"}""",
                )
        ).andExpect(status().isOk)
        mockMvc.perform(get("/news")).andExpect(status().isOk)
    }

    @Test
    fun `direct messages reach the inbox as Brainbox, are replyable, and stay out of notifications`() {
        val owner = admin("0755080020", listOf(PlatformPermission.PLATFORM_ADMIN))
        val ownerToken = token(owner)
        val learner = account("0755080021", Role.STUDENT)
        val learnerToken = token(learner)

        val message = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/console/messages").header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"title":"Welcome to Brainbox","body":"Your account is ready. Reply here if you need help.",""" +
                            """"audienceType":"USER","audienceValue":"${learner.id}"}""",
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ConsoleNotificationView::class.java,
        )
        check(message.channel == "MESSAGE") { message.channel }
        check(message.status == "SENT" && message.recipientCount == 1)

        // The recipient's own message centre shows it, authored by the platform account.
        val inbox: List<com.afrithecus.brainbox.api.messaging.web.MessagePayload> = objectMapper.readValue(
            mockMvc.perform(
                get("/messages/${learner.id}?folder=inbox").header("Authorization", auth(learnerToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(
                List::class.java,
                com.afrithecus.brainbox.api.messaging.web.MessagePayload::class.java,
            ),
        )
        val received = inbox.first { it.subject == "Welcome to Brainbox" }
        check(received.senderId == com.afrithecus.brainbox.api.messaging.PlatformSender.USER_ID.toString())
        check(received.senderName == "Brainbox") { received.senderName.toString() }
        check(received.senderRole == "SYSTEM") { received.senderRole.toString() }
        check(received.recipientName == learner.name)
        check(!received.isRead)

        // And it can be replied to: Brainbox is addressable from any account, and the answer is
        // readable in the console rather than being swallowed.
        mockMvc.perform(
            post("/messages/send").header("Authorization", auth(learnerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"recipientId":"${com.afrithecus.brainbox.api.messaging.PlatformSender.USER_ID}",""" +
                        """"subject":"Re: Welcome to Brainbox","body":"Thank you."}""",
                )
        ).andExpect(status().isOk)

        val replies: List<com.afrithecus.brainbox.api.messaging.web.MessagePayload> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/messages/replies").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(
                List::class.java,
                com.afrithecus.brainbox.api.messaging.web.MessagePayload::class.java,
            ),
        )
        val reply = replies.first { it.subject == "Re: Welcome to Brainbox" }
        check(reply.senderName == learner.name) { reply.senderName.toString() }
        check(reply.recipientName == "Brainbox")
        check(!reply.isRead)
        mockMvc.perform(
            post("/admin/console/messages/replies/${reply.id}/read").header("Authorization", auth(ownerToken))
        ).andExpect(status().isOk)
        val handled: List<com.afrithecus.brainbox.api.messaging.web.MessagePayload> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/messages/replies").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(
                List::class.java,
                com.afrithecus.brainbox.api.messaging.web.MessagePayload::class.java,
            ),
        )
        check(handled.first { it.id == reply.id }.isRead)

        // A direct message is correspondence, not an alert: it must not create a notification row.
        val notifications: List<ConsoleNotificationView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/notifications").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleNotificationView::class.java),
        )
        check(notifications.none { it.notificationId == message.notificationId })

        val history: List<ConsoleNotificationView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/messages").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleNotificationView::class.java),
        )
        check(history.any { it.notificationId == message.notificationId && it.channel == "MESSAGE" })

        // Capabilities are separate: notifications cannot be sent by a messages-only operator,
        // and a notifications-only operator cannot write into an inbox.
        val messenger = admin("0755080022", listOf(PlatformPermission.MESSAGES_MANAGE))
        val notifier = admin("0755080023", listOf(PlatformPermission.NOTIFICATIONS_MANAGE))
        mockMvc.perform(
            post("/admin/console/notifications").header("Authorization", auth(token(messenger)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Nope","body":"Body","audienceType":"USER","audienceValue":"${learner.id}"}""")
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/admin/console/messages").header("Authorization", auth(token(notifier)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Nope","body":"Body","audienceType":"USER","audienceValue":"${learner.id}"}""")
        ).andExpect(status().isForbidden)

        val messengerHistory: List<ConsoleNotificationView> = objectMapper.readValue(
            mockMvc.perform(
                get("/admin/console/messages").header("Authorization", auth(token(messenger)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleNotificationView::class.java),
        )
        check(messengerHistory.size == 1 && messengerHistory.first().notificationId == message.notificationId) {
            "a messages-only operator reads message history, not notifications"
        }
        // Only a capability holder reads the platform inbox.
        mockMvc.perform(
            get("/admin/console/messages/replies").header("Authorization", auth(token(notifier)))
        ).andExpect(status().isForbidden)

        // The platform sender is an account, not a person: it is absent from the account
        // surfaces and cannot be suspended, unverified or given a console role.
        val platformId = com.afrithecus.brainbox.api.messaging.PlatformSender.USER_ID.toString()
        val admins: List<ConsoleUserView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/users?role=ADMIN").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleUserView::class.java),
        )
        check(admins.none { it.userId == platformId }) { "the platform sender must not be listed as an account" }
        val operatorAccounts: List<ConsoleAccountView> = objectMapper.readValue(
            mockMvc.perform(get("/admin/console/roles/accounts").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ConsoleAccountView::class.java),
        )
        check(operatorAccounts.none { it.userId == platformId })
        mockMvc.perform(
            post("/admin/console/users/$platformId/active").header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":false}""")
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/admin/console/users/$platformId/password-reset").header("Authorization", auth(ownerToken))
        ).andExpect(status().isBadRequest)
    }

    // ---------------------------------------------------------------- helpers

    private fun identity(user: UserEntity): ConsoleIdentityPayload = objectMapper.readValue(
        mockMvc.perform(get("/admin/console/me").header("Authorization", auth(token(user))))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        ConsoleIdentityPayload::class.java,
    )

    private fun createUser(token: String, body: String): ConsoleCreatedUser = objectMapper.readValue(
        mockMvc.perform(
            post("/admin/console/users").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString,
        ConsoleCreatedUser::class.java,
    )

    private fun signIn(identifier: String, password: String) {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("identifier" to identifier, "password" to password)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(body, AuthResponse::class.java).sessionToken != null)
    }

    private fun admin(phone: String, capabilities: List<PlatformPermission>): UserEntity =
        account(phone, Role.ADMIN, capabilities)

    private fun account(
        phone: String,
        role: Role,
        capabilities: List<PlatformPermission> = emptyList(),
    ): UserEntity = users.save(
        UserEntity().apply {
            phoneNumber = phone
            email = phone + "@consolep.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Console " + role.name + " " + phone.takeLast(4)
            this.role = role
            isActive = true
            isVerified = true
            platformPermissions =
                capabilities.takeIf { it.isNotEmpty() }?.let { PlatformPermission.format(it.toSet()) }
        }
    )

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${user.phoneNumber}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token
}
