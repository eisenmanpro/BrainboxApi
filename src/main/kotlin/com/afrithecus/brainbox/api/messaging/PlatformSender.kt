package com.afrithecus.brainbox.api.messaging

import java.util.UUID

/**
 * The platform's own account: the sender of every message Brainbox writes into a user's inbox.
 *
 * It is seeded by the baseline migration with this fixed id so a message from the platform is
 * identifiable (the client presents it as SYSTEM rather than as an administrator), and it cannot
 * sign in — its password hash is a random secret nobody holds and it holds no console
 * capability. Messages arrive in the user's inbox exactly like any other message, so no client
 * change is needed to read one.
 */
object PlatformSender {
    val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000b0b0")
    const val NAME = "Brainbox"
    const val EMAIL = "brainbox@platform.local"
}
