# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Build & Run
- **Build**: `./gradlew build` (add `-x test` to skip tests)
- **Tests**: `./gradlew test`
- **Run locally**: `./gradlew bootRun`
- **Infra**: `docker compose up -d` (Postgres 17, plus optional Redis 7 — the app runs with `app.redis.enabled=false`)
- **Toolchain**: Java 21 (Gradle toolchain), Gradle wrapper 9.7.1
- **Serialize JVM builds**: Never run Android and API Gradle builds concurrently on this machine — one Gradle invocation at a time. Shared CPU and a slow link make concurrent builds dramatically slower. Web (`npm test`) is light and fine to overlap.

## Account model (F9/N6 — do not regress)

One account per person: a **learner's account is the guardian account**. `/parent/**` resolves a STUDENT's own
row; `PARENT` signup and ADMIN signup are refused (admins come from the platform console); legacy PARENT rows
are aliases only. "Parent" audiences deliver to the learner's inbox. Learner credentials are 4-digit PINs
(`credential_kind`), staff are 8+ passwords; login never validates shape. Full contract and enforcement map:
[docs/account_unification.md](docs/account_unification.md).

## Staff signup, email verification and the account-state gate

Staff signup (`POST /auth/signup/teacher`) requires an email, a school from the public directory
(`GET /auth/signup/schools`, then `/schools/{id}` for its grades/classes) and a sub-role; grades/classes must
exist in the school's catalogue and an ICT admin takes neither. A class teacher takes one grade + one class; a
**grade coordinator is a class teacher with grade-wide duties** and takes the same one grade + one class, with
**one coordinator per grade per school** enforced by `CoordinatorGradeGuard` (school-row lock, so concurrent
signups cannot both win; rejecting/freezing the holder frees the slot) and by the ICT admin's School Config
assignment surface (`GET .../coordinator-candidates`, `PUT .../grades/{gradeLevel}/coordinator`, which demotes a
replaced holder back to `CTEACHER` and keeps their class). Elevated coordinator authority is
**grade-scoped** (own school + own grade) through `GradeAuthority`; ordinary teaching access in another grade is
additive. Matrix and check order: [docs/coordinator_authority.md](docs/coordinator_authority.md). Signup never
returns a session: a 6-digit code
is emailed (Mailjet SMTP via `app.mail.provider=mailjet`, credentials `MJ_APIKEY_*`, sender `MAIL_FROM`) and
`POST /auth/email/verify` starts it. `POST /auth/login` on an unconfirmed address answers 403
`EMAIL_NOT_VERIFIED`. `AccountStateFilter` refuses staff data routes with `ACCOUNT_PENDING` until an ICT admin
approves and `EMAIL_NOT_VERIFIED` until the address is confirmed, so a pending account has no power even with a
token; `auth/validate-ctc` refuses a pending teacher's code. `POST /auth/recover-email` returns only the masked
address, and password-reset codes go through the same transactional email transport. Contract and env table:
[docs/email_verification_and_signup.md](docs/email_verification_and_signup.md).

## Phone verification (dormant by default)

Server-side OTP over an SMS gateway; **HostPinnacle** is the decided default (owner account active), with
**Africa's Talking** and **Beem** retained as config-selectable fallback. It is implemented behind
`app.auth.phone-verification.enabled=false`: no SMS is sent and the endpoints answer "not enabled" until an operator
configures credentials. HostPinnacle requires a **purchased sender ID** (support confirms there are no test sender
IDs); until it is bought, Africa's Talking's sandbox is the live delivery test channel and production stays on the
logging/dormant path. Do not make any UI claim a phone is verified unless `users.phone_verified_at` is set, and
clear `phone_verified_at` whenever a phone number changes. Decision, provider research and activation checklist:
[docs/phone_verification.md](docs/phone_verification.md), the phased implementation plan
[docs/sms_and_attendance_notifications_plan.md](docs/sms_and_attendance_notifications_plan.md) and the operator
runbook [docs/attendance_sms_runbook.md](docs/attendance_sms_runbook.md).

## Attendance notifications and the guarded SMS fallback (dormant by default)

Attendance fan-out is in-app + push for every non-deactivated account (D1). The paid SMS fallback is
**off by default** (`app.attendance.sms.enabled=false`); when enabled it is ABSENT-only, after register close
(10:30 school zone, Mon–Fri quiet window), D1 subscription-eligible, verified-phone only, D2 explicit opt-in,
at most one per family/phone/day (a legacy PARENT alias and the learner account are one family inbox — never
send to both), and drawn from a KES 2,000/school/term allocation that stops SMS at zero while push/in-app
continue. Phase 4 refines the capability gate: a recipient with an active push token is not skipped outright;
the SMS waits until the in-app absence row is unread for `read-ack-minutes` (default 20; a read row cancels,
no row means the push was never attempted and the fallback applies), a family digest lists every absent sibling
in the one paid message (extra records keep a zero-cost `MERGED` ledger row), an optional risk threshold
(default off) can require a consecutive-absence/term-attendance signal, and delivery may fall to configured
fallback providers through a circuit breaker. There is no automatic retry. Provider DLRs arrive at
`POST /webhooks/sms/dlr` (shared secret, blank disables it) and appear on the ledger; EN/SW templates are
selected by `ATTENDANCE_SMS_LANGUAGE`. OTP and attendance share the `com.afrithecus.brainbox.api.sms`
gateway classes. Ledger: `/admin/console/sms/**` (platform) and `/school/attendance-sms/**` (own
school). Contract, rule order, config keys and cost template:
[docs/sms_and_attendance_notifications_plan.md](docs/sms_and_attendance_notifications_plan.md) §6/§15.
P6.0 adds a **staging-only DLR simulator** (controller/scheduler exist only under the `staging` profile, the
service refuses other profiles, and the staging yml enables it by default) so the ledger and delivery path can be
exercised without a provider; it must never be enabled in production. Rollout, monitoring, incidents, key
rotation and the emergency brakes are in [docs/attendance_sms_runbook.md](docs/attendance_sms_runbook.md). Live
HostPinnacle attendance delivery also waits on the purchased sender ID.
