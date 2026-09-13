package com.afrithecus.brainbox.api.push

import java.util.UUID

/** Read/prune surface the push fan-out needs, kept small so it is easy to fake. */
interface DeviceTokenStore {
    fun tokensFor(userId: UUID): List<String>
    fun deleteTokens(userId: UUID, tokens: Collection<String>)
}
