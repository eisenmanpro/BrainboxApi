package com.afrithecus.brainbox.api.notification

/**
 * The one-time "you also have a web workspace" notice
 * (docs/ongoing/product_ops_roadmap.md item 6).
 *
 * The body is built from the configured web address, and deliberately does **not** fall back to a
 * guessed domain: telling a teacher to visit a host that does not exist is worse than telling them
 * to ask for the address. Set `app.web-app-url` (or `WEB_APP_URL`) per environment.
 */
object WebCounterpartCopy {

    const val TITLE = "Brainbox also has a web workspace"

    fun body(webAppUrl: String?): String {
        val url = webAppUrl?.trim().orEmpty()
        val opening = "You can also run your classroom from a computer - mark attendance, set " +
            "homework, build exams and read reports"
        return if (url.isEmpty()) {
            opening + " on the Brainbox web workspace. Ask your school administrator for the " +
                "address, then sign in with the same account you use here."
        } else {
            opening + " at " + url + ". Sign in with the same account you use here."
        }
    }
}
