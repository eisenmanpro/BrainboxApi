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
configures credentials. Do not make any UI claim a phone is verified unless `users.phone_verified_at` is set, and
clear `phone_verified_at` whenever a phone number changes. Decision, provider research and activation checklist:
[docs/phone_verification.md](docs/phone_verification.md) and the phased implementation plan
[docs/sms_and_attendance_notifications_plan.md](docs/sms_and_attendance_notifications_plan.md).
