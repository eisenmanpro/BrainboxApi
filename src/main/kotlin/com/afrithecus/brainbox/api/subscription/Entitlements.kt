package com.afrithecus.brainbox.api.subscription

import com.afrithecus.brainbox.api.identity.model.AccessLevel
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier

/**
 * Maps role + subscription to the login accessLevel (doc 01 §1.1).
 * Teachers/parents/admins bypass subscriptions entirely; unverified students are
 * capped at BASE regardless of paid tier (doc 01 §8.1).
 */
object Entitlements {

    fun accessLevel(
        role: Role,
        subscriptionStatus: SubscriptionStatus,
        subscriptionTier: SubscriptionTier,
        isVerified: Boolean,
    ): AccessLevel {
        if (role == Role.TEACHER || role == Role.PARENT || role == Role.ADMIN) return AccessLevel.FULL
        val effectiveTier = if (isVerified) subscriptionTier else SubscriptionTier.BASE
        return when {
            effectiveTier == SubscriptionTier.BASE -> AccessLevel.LIMITED
            subscriptionStatus == SubscriptionStatus.ACTIVE -> AccessLevel.FULL
            else -> AccessLevel.LIMITED
        }
    }
}
