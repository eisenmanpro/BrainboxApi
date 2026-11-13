package com.afrithecus.brainbox.api.mail

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Transactional email settings (gaps.md items 3/4). BrainBox sends signup verification codes and
 * password-reset codes; both go through one [TransactionalEmailSender] seam so the transport can be
 * swapped without touching the auth services.
 *
 * Provider credentials are environment-only and never logged: MJ_APIKEY_PUBLIC / MJ_APIKEY_PRIVATE
 * are the Mailjet SMTP username/password, MAIL_FROM is the validated Mailjet sender address.
 */
@ConfigurationProperties(prefix = "app.mail")
data class MailProperties(
    /** logging (development: nothing is delivered) | mailjet (SMTP through in-v3.mailjet.com). */
    val provider: String = "logging",
    /** Validated sender address. Mailjet rejects a send whose From is not a verified sender. */
    val fromAddress: String = "",
    val fromName: String = "BrainBox",
    /** Development only: print the whole message body, which contains the one-time code. */
    val logBody: Boolean = false,
)
