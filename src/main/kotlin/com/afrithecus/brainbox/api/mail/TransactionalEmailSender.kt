package com.afrithecus.brainbox.api.mail

/**
 * Out-of-band delivery seam for one transactional email. Implementations must throw on a delivery
 * failure: the auth services turn that into a 503 instead of pretending the mail was sent.
 */
interface TransactionalEmailSender {

    /** False for a development sink, where no real message leaves the process. */
    val configured: Boolean

    fun send(to: String, subject: String, textBody: String)
}
