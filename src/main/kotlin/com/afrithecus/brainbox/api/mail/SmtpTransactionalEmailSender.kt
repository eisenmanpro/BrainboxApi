package com.afrithecus.brainbox.api.mail

import jakarta.mail.internet.MimeMessage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/**
 * Mailjet SMTP transport (in-v3.mailjet.com, credentials in spring.mail). The account allows a
 * sender only after it is verified in Mailjet, so an unverified MAIL_FROM fails loudly at send
 * time rather than silently dropping the code.
 */
@Component
@ConditionalOnProperty(prefix = "app.mail", name = ["provider"], havingValue = "mailjet")
class SmtpTransactionalEmailSender(
    private val mailSender: JavaMailSender,
    private val properties: MailProperties,
) : TransactionalEmailSender {

    override val configured: Boolean
        get() = properties.fromAddress.isNotBlank()

    override fun send(to: String, subject: String, textBody: String) {
        val from = properties.fromAddress.trim()
        if (from.isEmpty()) {
            throw IllegalStateException("MAIL_FROM must be set when app.mail.provider=mailjet")
        }
        val message: MimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, false, "UTF-8")
        helper.setFrom(from, properties.fromName.trim().ifEmpty { "BrainBox" })
        helper.setTo(to)
        helper.setSubject(subject)
        helper.setText(textBody, false)
        mailSender.send(message)
    }
}
