package com.afrithecus.brainbox.api.messaging.model

/** Mailbox folder (doc 05 §2.1); lowercase names match the wire/DB values. */
enum class Folder { inbox, sent, outbox }

/** Teacher send audience (doc 05 §2.6). */
enum class AudienceType { INDIVIDUAL, CLASS, CLASS_PARENTS }
