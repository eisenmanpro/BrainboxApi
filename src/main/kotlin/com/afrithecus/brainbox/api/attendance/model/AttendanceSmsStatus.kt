package com.afrithecus.brainbox.api.attendance.model

/** One dispatch row's lifecycle: reserved before the provider call, finalised after it. */
enum class AttendanceSmsStatus {
    /** Budget reserved and row written; the provider call is in flight. */
    PENDING,

    /** The provider accepted the message. */
    SENT,

    /** The provider rejected it or was unreachable; single attempt, no automatic retry. */
    FAILED,
}
