package com.afrithecus.brainbox.api.push

import org.junit.jupiter.api.Test

/** FCM payload shaping (docs/ongoing/api_push_changes.md). */
class FcmPayloadsTest {

    @Test
    fun `data payload carries the client contract keys`() {
        val data = FcmPayloads.data(
            PushMessage(
                title = "Absence Alert: Alice",
                message = "Alice was marked absent.",
                type = "ATTENDANCE",
                actionRoute = "student_report/s_1",
                actionLabel = "View report",
                urgency = "HIGH",
                metadata = mapOf("status" to "ABSENT", "studentId" to "s_1"),
            ),
        )
        check(data["title"] == "Absence Alert: Alice")
        check(data["type"] == "ATTENDANCE")
        check(data["actionRoute"] == "student_report/s_1")
        check(data["actionLabel"] == "View report")
        check(data["urgency"] == "HIGH")
        check(data["status"] == "ABSENT")
        check(data["studentId"] == "s_1")
    }

    @Test
    fun `blank optionals are omitted and urgency maps to android priority`() {
        val data = FcmPayloads.data(PushMessage(title = "T", message = "M", actionRoute = "", urgency = "  "))
        check(!data.containsKey("type"))
        check(!data.containsKey("actionRoute"))
        check(!data.containsKey("urgency"))

        val high = FcmPayloads.requestBody("tok", PushMessage(title = "T", message = "M", urgency = "HIGH"), "system_notifications_channel")
        val highMessage = high["message"] as Map<*, *>
        val highAndroid = highMessage["android"] as Map<*, *>
        check(highAndroid["priority"] == "high")
        check((highAndroid["notification"] as Map<*, *>)["channel_id"] == "system_notifications_channel")
        check(highMessage["token"] == "tok")
        check((highMessage["notification"] as Map<*, *>)["title"] == "T")

        val normal = FcmPayloads.requestBody("tok", PushMessage(title = "T", message = "M", urgency = "NORMAL"), "ch")
        check(((normal["message"] as Map<*, *>)["android"] as Map<*, *>)["priority"] == "normal")
    }
}
