package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ModerationPolicyEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ModerationPolicyRepository : JpaRepository<ModerationPolicyEntity, java.util.UUID> {

    fun findByPolicyKey(policyKey: String): ModerationPolicyEntity?
}
