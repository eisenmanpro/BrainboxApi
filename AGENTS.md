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

## Phone verification (dormant by default)

Server-side OTP over an SMS gateway; **HostPinnacle** is the decided default (owner account active), with
**Africa's Talking** and **Beem** retained as config-selectable fallback. It is implemented behind
`app.auth.phone-verification.enabled=false`: no SMS is sent and the endpoints answer "not enabled" until an operator
configures credentials. HostPinnacle requires a **purchased sender ID** (support confirms there are no test sender
IDs); until it is bought, Africa's Talking's sandbox is the live delivery test channel and production stays on the
logging/dormant path. Do not make any UI claim a phone is verified unless `users.phone_verified_at` is set, and
clear `phone_verified_at` whenever a phone number changes. Decision, provider research and activation checklist:
[docs/phone_verification.md](docs/phone_verification.md) and the phased implementation plan
[docs/sms_and_attendance_notifications_plan.md](docs/sms_and_attendance_notifications_plan.md).

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
[docs/sms_and_attendance_notifications_plan.md](docs/sms_and_attendance_notifications_plan.md) §6/§15. Live
HostPinnacle attendance delivery also waits on the purchased sender ID.
