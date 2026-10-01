package com.afrithecus.brainbox.api.about

import com.afrithecus.brainbox.api.about.web.FaqCategoryPayload
import com.afrithecus.brainbox.api.about.web.FaqItemPayload
import com.afrithecus.brainbox.api.about.web.FaqPayload
import com.afrithecus.brainbox.api.about.web.HumanElementPayload
import com.afrithecus.brainbox.api.about.web.NarrativePayload
import com.afrithecus.brainbox.api.about.web.PartnerPayload
import com.afrithecus.brainbox.api.about.web.PracticalDetailPayload
import com.afrithecus.brainbox.api.about.web.SocialProofPayload
import org.springframework.stereotype.Service

/**
 * About + FAQ content (docs/ongoing/product_ops_roadmap.md item 7).
 *
 * The narrative/social/practical/human values are the same seed content the Android
 * client ships as `MockAboutApi` and the web client as `api.js` mocks, so wiring the
 * endpoints changes nothing visually until approved copy replaces them.
 *
 * NOTE FOR RELEASE: the social-proof figures are seed values, not measured ones. They
 * must be replaced with figures someone can stand behind before the landing page is public.
 */
@Service
class AboutService {

    fun narratives(): NarrativePayload = NarrativePayload(
        missionStatement = "Democratizing quality education across Africa through technology",
        story = "BrainBox was founded with a singular vision: to break down the barriers that keep " +
            "talented learners across Africa from accessing world-class education. We believe that " +
            "every student, regardless of their school's resources or location, deserves the tools to " +
            "discover their potential, track their progress, and prepare for meaningful careers.",
        vision = "To become Africa's most trusted learning companion, empowering every learner with " +
            "personalized, competency-based education",
    )

    fun humanElements(): List<HumanElementPayload> = listOf(
        HumanElementPayload(
            founderName = "Pauline Mumbi",
            profession = "Software Engineer",
            photo = null,
            bio = "Talk is cheap. Show me code",
            bioPersona = "Linus Torvalds",
        ),
        HumanElementPayload(
            founderName = "John Ambrose",
            profession = "Philosopher",
            photo = null,
            bio = "The function of education is to teach one to think intensively and critically",
            bioPersona = "Martin Luther King Jr",
        ),
        HumanElementPayload(
            founderName = "Pauline Mumbi",
            profession = "Software Engineer",
            photo = null,
            bio = "It's fine to celebrate success but it is more important to heed the lesson of failure",
            bioPersona = "Bill Gates",
        ),
    )

    fun socialProof(): SocialProofPayload = SocialProofPayload(
        userBase = 10500,
        yearsOfService = 1,
        clients = listOf(
            PartnerPayload("Peter Kimani", null, "When i introduced BrainBox to my Childs, i thought this is another scam platform. i decided to give it a month to try out now i recommend"),
            PartnerPayload("Felix Otieno", null, "BrainBox is the only platform that seems to do something about the future of education here in Kenya"),
            PartnerPayload("Benson Mbatha", null, "I would be happy to see this ecosystem integrate with higher education Institutions"),
        ),
    )

    fun practicalDetails(): PracticalDetailPayload = PracticalDetailPayload(
        location = "Nairobi",
        careerLink = "careers.brainbox.com",
        contactInfo = "info@brainbox.com",
    )

    /**
     * FAQs live on the server so the app and the web page can never drift. Answers are
     * written from the product's actual behaviour — subscriptions, report allowances and
     * the offline rules below are the same invariants the API enforces.
     */
    fun faqs(): FaqPayload = FaqPayload(
        version = FAQ_VERSION,
        categories = listOf(
            FaqCategoryPayload("getting-started", "Getting started", listOf(
                FaqItemPayload("what-is-brainbox", "What is BrainBox?", "BrainBox is a learning platform for Kenyan secondary schools. Learners practise and sit CBC and traditional exams, while teachers run their classroom — roster, attendance, homework, exams, reports and live classes — from one place."),
                FaqItemPayload("where-can-i-use-it", "Can I use BrainBox on a phone and a computer?", "Yes. The Android app is the learner and teacher companion, and teachers also have a web workspace for marking, planning and reports. Sign in with the same account on both."),
                FaqItemPayload("internet", "Does BrainBox need internet all the time?", "No. Most screens are offline-first: the app shows what it last loaded and queues your writes to sync when a connection returns. Reports, live classes and messaging are the parts that need the network."),
                FaqItemPayload("signup", "How do I create an account as a learner or a parent?", "Sign up with your name, phone number and a password, choosing learner or parent. A learner who has the class teacher code (CTC) from their teacher enters it to join that class and school straight away. Pick your school from the list, or choose \"my school is not listed\" and give its name for review."),
                FaqItemPayload("teacher-signup", "How does a teacher join?", "Teachers sign up with their own details, their school, and the classes and subjects they teach. The school verifies the account before it is fully active, and you receive a class teacher code (CTC) to share with your learners so they can join your class. Coordinator and ICT admin roles are assigned within the school rather than chosen at signup."),
                FaqItemPayload("login", "How do I log in?", "Sign in with the phone number you registered, or your email address, and your password. Learners, parents and teachers use the same sign-in screen and BrainBox opens the workspace for your role. On a borrowed phone leave \"shared or borrowed device\" ticked, so your data is cleared when the session ends."),
                FaqItemPayload("forgot-password", "What if I forget my password?", "Choose \"Forgot password\", enter your phone number, and use the one-time code we send you to set a new password. A school administrator can also reset a password for you."),
            )),
            FaqCategoryPayload("accounts-roles", "Accounts and roles", listOf(
                FaqItemPayload("roles", "What is the difference between a class teacher and a grade coordinator?", "A class teacher (C.Teacher) looks after their own class and roster. A grade coordinator oversees a whole grade — results, analytics and grade transitions. An ICT admin manages school-wide settings."),
                FaqItemPayload("no-smartphone", "What about learners who have no smartphone?", "Teachers can provision a roster-only learner record. It holds marks, ranking and reports for that pupil without needing a login, and can be upgraded to a full account later if they get a device."),
                FaqItemPayload("parent-link", "How do parents see their child's progress?", "A parent account is linked to the learner. Once results are finalised the parent receives the report on their phone and can track progress from the parent dashboard."),
            )),
            FaqCategoryPayload("subscriptions", "Subscriptions and billing", listOf(
                FaqItemPayload("plans", "Which plans are available?", "BASE is free. EXPLORER and PRO are monthly plans that unlock contests, analytics and unlimited practice. Explorer and Pro learners share the plan with their linked parent."),
                FaqItemPayload("expiry", "What happens when my plan expires?", "You keep reading your history, but exam analytics, new homework, attendance and results publishing pause until the plan is renewed. BrainBox reminds you before and after the expiry date."),
                FaqItemPayload("renew", "How do I renew?", "From the subscription screen in the app, or the prompt in the renewal reminder. Payment is by M-Pesa and the plan activates for another month."),
            )),
            FaqCategoryPayload("reports", "Reports and results", listOf(
                FaqItemPayload("free-reports", "Which reports can I download as often as I like?", "Grade analysis, the combined grade report and class lists are always free and unlimited. They are the teaching tools, not the result slips."),
                FaqItemPayload("student-limit", "Why is there a limit on student reports?", "A teacher account can export ten student reports in total. After that, BrainBox asks parents to download from their own devices — results are dispatched to the learner's and parent's phones once an exam is finalised."),
                FaqItemPayload("mass-download", "When can I download a whole class or grade at once?", "Mass download of student reports is allowed when at least 80% of that class or grade is on an active Explorer or Pro plan. Below that, individual reports remain available up to your allowance."),
            )),
            FaqCategoryPayload("classroom", "Homework and attendance", listOf(
                FaqItemPayload("attendance", "How do I mark the register quickly?", "Open the daily register and mark by exception: learners are present unless you say otherwise. You can bulk-mark, auto-mark from a live class, and reuse the Mon–Sat weekly view."),
                FaqItemPayload("homework", "How does homework work?", "Create it for a class or specific learners, with a due date and submission type. Learners submit from the app and you grade against feedback templates; everything queues offline and syncs later."),
                FaqItemPayload("parents-attendance", "Are parents told about absences?", "Yes. When a register is submitted, guardians receive a notification for any learner marked absent, so the school and home are never out of step."),
            )),
            FaqCategoryPayload("live-and-messaging", "Live classes and messaging", listOf(
                FaqItemPayload("live", "Can I teach a live class in BrainBox?", "Yes. Schedule a session, then host it from the host console: camera and microphone controls, screen sharing, polls and attendance are recorded against the session."),
                FaqItemPayload("recording", "Can I share a recording afterwards?", "Sessions can be recorded and the recording is listed with the class so learners who missed it can catch up."),
                FaqItemPayload("messages", "Who can I message?", "You can message your own classes and colleagues in your school. BrainBox itself also writes to your inbox for platform announcements, and you can reply to those."),
            )),
            FaqCategoryPayload("privacy", "Privacy and shared devices", listOf(
                FaqItemPayload("borrowed-device", "A learner is using a borrowed phone. Is their data safe?", "The sign-in screen has a shared-device option, on by default. Untrusted sessions erase tokens, cached reports, rosters and queued work when the session ends or goes idle."),
                FaqItemPayload("data", "Who can see a learner's marks?", "The learner, their linked parent, and the teachers responsible for their classes. Class teachers are scoped to their own classes; coordinators to their grade and school."),
            )),
        ),
    )

    private companion object {
        /** Bump when the questions change so caches revalidate. */
        const val FAQ_VERSION = "2026-09-30.2"
    }
}
