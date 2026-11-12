# SMS provider, attendance notifications and offline delivery — phased implementation plan

**Status: PLAN ONLY — no code changed by this document.** Written 2026-11-12, the afternoon before the HostPinnacle
credentials arrive. It turns the research in [phone_verification.md](phone_verification.md) and the attendance/offline
audit into an ordered, testable task list.

| | |
|---|---|
| **Repos** | `BrainboxApi-master` (primary), `BrainBox-master` (Android), `BrainboxWeb-main` (web) |
| **Related records** | [phone_verification.md](phone_verification.md) (provider research, OTP slice), [account_unification.md](account_unification.md) (F9: learner account is the guardian), `.design-review/f9-account-unification-audit.md`, `.design-review/audit-rubric-and-backlog.md` (Android repo) |
| **Decision supersedes** | "Africa's Talking primary, Beem fallback" in `phone_verification.md` — HostPinnacle becomes the default; AT/Beem stay as config-selectable fallback |
| **Blocked on** | HostPinnacle credentials + sender ID + API response sample (Phase 0/1 live steps only) |

**Implementation can start before the credentials arrive**: Phase 1's notifier scaffold, Phase 2 (subscription routing)
and Phase 5 (offline fixes) are all credential-independent and testable against a local HTTP stub.

---

## 1. Decisions locked

1. **HostPinnacle is the default SMS provider** for both phone-verification OTP and the future attendance SMS channel.
   Africa's Talking and Beem remain implemented and config-selectable as fallback; nothing is deleted. The provider
   decision was driven by the owner's research: both AT and Beem require a dedicated sender-ID registration (the AT
   default sender ID is Kenya/Airtel-only for testing), while HostPinnacle supplies a working sender ID without the
   telco paperwork, and the owner already has an active HostPinnacle account with test credit.
2. **OTP over an SMS gateway stays the architecture.** We generate, hash, expire and rate-limit the code; the gateway
   only delivers. No client SDK, no second identity authority.
3. **Attendance notifications are push/in-app first; SMS is a guarded fallback, not a broadcast channel.** Every rule
   below exists to avoid sending an SMS that push already covers.
4. **A mark is never lost offline.** Server rows are the source of truth; clients replay pending registers. The gaps
   are delivery catch-up (missed push), late-synced escalation, and the absence of any SMS/feature-phone channel.
5. **Attendance routing must become subscription-aware** (current behaviour: it is not — see §2.3). Recommended policy:
   in-app history always, push/SMS only for learners whose subscription is ACTIVE. The final call is decision D1.

## 2. Current state — evidence

### 2.1 Phone verification (shipped, dormant)

Already implemented, tested and committed: V12 (`phone_verification_challenges` + `users.phone_verified_at`),
authenticated request/confirm/status endpoints, hashed 6-digit code, TTL/attempts/cooldown/rate limits, provider seam
`PhoneVerificationNotifier` with `AfricasTalkingSmsNotifier` and `BeemSmsNotifier`, and the Android/web cards.
Dormant behind `app.auth.phone-verification.enabled=false` ([application.yml](../src/main/resources/application.yml:176)).
The only missing piece for OTP is the HostPinnacle provider + credentials.

### 2.2 Attendance fan-out (server)

`AttendanceService.submit` writes each register row and, on a new row or a status transition into
`{ABSENT, LATE, PRESENT}`, calls `notifyAttendanceAudience` ([AttendanceService.kt](../src/main/kotlin/com/afrithecus/brainbox/api/attendance/AttendanceService.kt:118)).
Recipients are **the learner's legacy `parentUserId` alias (if any) plus the learner's own account**
([AttendanceService.kt:643](../src/main/kotlin/com/afrithecus/brainbox/api/attendance/AttendanceService.kt:643)); under F9 the
learner account IS the guardian account, so in practice this is one inbox. Mark-by-exception records missing roster
learners as ABSENT and fans out the same way.

At the configured school-time threshold (default 10:00, `app.attendance.escalation-time`), the scheduler sweeps
today's still-ABSENT rows and sends one URGENT re-notification per record, made idempotent by the unique
`attendance_escalations` row ([AttendanceService.kt:594](../src/main/kotlin/com/afrithecus/brainbox/api/attendance/AttendanceService.kt:594),
[AttendanceEscalationScheduler.kt](../src/main/kotlin/com/afrithecus/brainbox/api/attendance/AttendanceEscalationScheduler.kt:17)).

Push is attempted only when `shouldPush` allows it: an URGENT attendance event is **always** pushed, every other event
honours the global push switch and category switches; in-app rows are written regardless
([NotificationService.kt:423](../src/main/kotlin/com/afrithecus/brainbox/api/notification/NotificationService.kt:423)).
`notifyUser` itself performs no user-existence, active-account or subscription check
([NotificationService.kt:101](../src/main/kotlin/com/afrithecus/brainbox/api/notification/NotificationService.kt:101)).

### 2.3 Is attendance routed to users with an active subscription? — **No.**

There is no subscription gate anywhere in the attendance or notification path today:

- Recipients are chosen from `parentUserId` + the learner id only; no `SubscriptionService` call, no tier/status check,
  no `isActive` check. An expired or BASE learner (and a deactivated account) still receives in-app and push absence
  alerts.
- `LearnerAccess.requireFull` sounds like a subscription gate but is not: it only rejects `ROSTER_ONLY` learners
  (no-smartphone, traditional-exam-only) from attendance
  ([LearnerAccess.kt:18](../src/main/kotlin/com/afrithecus/brainbox/api/identity/LearnerAccess.kt:18)).
- The only subscription logic in the platform is `Entitlements.accessLevel`: teachers/parents/admins are always FULL,
  and a learner is FULL only when verified + non-BASE + ACTIVE, else LIMITED
  ([Entitlements.kt:15](../src/main/kotlin/com/afrithecus/brainbox/api/subscription/Entitlements.kt:15)). That value is
  computed at login and returned in the session payload
  ([AuthService.kt:360](../src/main/kotlin/com/afrithecus/brainbox/api/auth/AuthService.kt:360)); **no server filter or
  service enforces it**. Only the Android/web clients use it to hide UI (`FeatureAccessManager` — parent tier mapping at
  [FeatureAccessManager.kt:72](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/core/auth/FeatureAccessManager.kt:72),
  `VIEW_CHILD_ATTENDANCE` at line 416).
- Subscription expiry is recomputed on read (`SubscriptionService.view`), so an "active but past expiry" row is treated
  as EXPIRED elsewhere — but that result is never consulted by attendance.

**Consequence if SMS is added without a gate:** paid SMS would be spent on absentees whose families have not paid,
including long-expired accounts. The cost effect is modest (most marked learners are active) but the billing/product
effect is not: it gives a paid channel away for free and contradicts the subscription model. It also means a
subscription-gated rollout is a policy change, not a refactor — hence Phase 2 before any SMS code.

**Recommended routing rule (D1):**

| Channel | Expired / BASE learner | ACTIVE non-BASE learner | Staff (teacher/admin) |
|---|---|---|---|
| In-app notification row | keep (history + safety) | yes | not an attendance recipient |
| FCM push | no | yes (subject to push settings) | n/a |
| Attendance SMS | no | only if consent + capability gates pass | n/a |
| Subscription renewal nudge | existing reminder service | existing reminder service | n/a |

Legacy `PARENT` rows bypass subscriptions by role; under F9 they are aliases, so eligibility must be inherited from the
learner they alias, not from the role. A grace window after expiry (`ATTENDANCE_ROUTING_GRACE_DAYS`, default 3) is
recommended so a payment lapse does not silently cut off a week of absence alerts; this is part of D1.

### 2.4 Offline delivery

| Scenario | Current behaviour | Gap |
|---|---|---|
| Teacher offline (Android) | Register cached in Room as pending, replayed by the 30-minute WorkManager periodic sync ([SyncManager.kt:16](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/data/sync/SyncManager.kt:16), [SyncWorker.kt:61](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/data/sync/workers/SyncWorker.kt:61), [TeacherAttendanceRepository.kt:146](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/teacher/data/repository/TeacherAttendanceRepository.kt:146)) | Cross-device fan-out waits for connectivity; replay can take up to 30 min; the pending replay does not refresh notifications afterwards |
| Teacher offline (web) | Register goes to the web write outbox with retries | Same, no local fallback alert |
| Parent phone offline | In-app row persists server-side; FCM queues high-priority messages | A missed/expired push gives no badge or feed update until the Notifications screen is opened; `SyncWorker` never refreshes notifications and `NotificationsViewModel` refreshes only in `init` ([NotificationsViewModel.kt:43](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/viewmodels/NotificationsViewModel.kt:43)) |
| FCM/provider down | Row persists; sender retries transient errors; fan-out records a metric | No delivery ledger/proof; no SMS fallback |
| Escalation + late sync | Sweep only looks at *today* | A register synced after midnight never escalates (the absence notice is lost; the record itself is fine) |
| Web parent/learner | Notification centre refreshes when opened; cached offline | No real-time web notifications at all |

## 3. Target behaviour

1. A register mark always produces exactly one in-app notification row per eligible recipient, never duplicated by a
   replay or restart.
2. Push is attempted only for eligible recipients; an SMS is sent only when the push channel cannot reach the guardian,
   the guardian is eligible and consented, a budget remains, and no earlier/deduplicated send covers the same event.
3. Expired/BASE subscriptions and deactivated accounts receive no push/SMS (in-app per D1); a grace window is
   configurable.
4. A register synced late still yields its escalation (same day by scheduler, up to N previous days by catch-up).
5. Clients catch up missed notifications on resume/reconnect, so a lost push is never a lost notification.
6. Every SMS is auditable (who, why, which record, provider id, cost/credits, outcome) and budget-capped per school.

## 4. Phases

Estimates are focused engineering time, one developer, tests included. "Repo" is where the write happens.

### Phase 0 — Inputs and decisions (0.5 day; partially owner-driven)

| ID | Task | Depends | Est | Acceptance |
|---|---|---|---|---|
| P0.1 | Owner supplies HostPinnacle credentials, sender ID, API sample, pricing/credit facts and test numbers (§11) | owner | — | Secrets readable from a git-ignored file; one live curl send succeeds |
| P0.2 | Record D1–D7 decisions (§10) in this doc | owner | 0.5 h | "Decision" lines filled in, not guessed |
| P0.3 | Baseline measurement on staging/prod read-only: absence events/day, % guardians with a push token, sibling overlap, pre-cutoff correction rate, notification read rate | none | 2 h | Numbers table appended to §8; these drive thresholds |
| P0.4 | Agree SMS templates (EN + SW, ≤160 GSM-7 chars) and consent copy | owner | 0.5 h | Strings in this doc |

### Phase 1 — HostPinnacle as the default provider + OTP activation (~1 day)

| ID | Task | Repo/files | Depends | Est | Acceptance |
|---|---|---|---|---|---|
| P1.1 | `hostpinnacle` provider block: API key, portal username/password, sender ID, base URL (`https://smsportal.hostpinnacle.co.ke/SMSApi`) | `PhoneVerificationProperties.kt`, `application.yml` | — | 30 min | Env-only credentials; `configured()` refuses partial config |
| P1.2 | `HostpinnacleSmsNotifier`: form POST with `apikey` header and `userid/password/msg/sendMethod=quick/senderid/msgType=text/output=json/duplicatecheck=true/mobile=254…`; single attempt, 3–5 s timeouts, no credential logging | `src/main/kotlin/.../auth/HostpinnacleSmsNotifier.kt` | P1.1 | 2 h | Unit-tested payload/header/format; failure surfaces 503 from the OTP endpoint with no challenge row |
| P1.3 | Number formatting: `07…`, `254…`, `+254…` → `2547…`; unit tests | notifier + `PhoneNumbers` tests | P1.2 | 30 min | All three shapes covered |
| P1.4 | Pin the response contract: live send once, capture success and error bodies, encode success/failure classification; stub tests for 2xx-success, 2xx-body-error, 4xx, 5xx, timeout | `PhoneVerificationProviderTests.kt` | P0.1 | 2 h | Classification matches captured samples; no "any 2xx = sent" assumption |
| P1.5 | Default wiring: `provider: hostpinnacle` (flag still off), `logging` remains the dev fallback; document precedence | `application.yml` | P1.1 | 15 min | Boot with empty creds fails fast only when enabled; dormant otherwise |
| P1.6 | Live smoke: OTP to Safaricom/Airtel/Telkom test numbers, confirm sender-ID display, delivery time, capture evidence | staging | P0.1, P1.4 | 1 h | 3/3 delivered; response + timing recorded |
| P1.7 | Docs: amend `phone_verification.md` (HostPinnacle default, AT/Beem fallback), `AGENTS.md` line 23, this plan's status | docs | P1.5 | 30 min | No doc still says AT is primary |
| P1.8 | Credit health check (optional here, recommended): `/account/readstatus` scheduled probe + ops alert when credits low | `src/main/kotlin/.../ops` | P1.4 | 3 h | Alert fires below threshold in a test profile |

**Flow after this phase:** mark absent → teacher taps verify → OTP SMS via HostPinnacle → phone verified. Not yet live
to users until the flag is flipped.

### Phase 2 — Subscription-aware attendance routing (~1.5 days; credential-independent)

| ID | Task | Repo/files | Depends | Est | Acceptance |
|---|---|---|---|---|---|
| P2.1 | `AttendanceRecipientPolicy`: eligibility = user exists, `isActive`, `accountKind=FULL`, learner subscription `ACTIVE`, tier ≠ `BASE`, expiry > now (+ `ATTENDANCE_ROUTING_GRACE_DAYS`); legacy parent alias inherits from the learner, not from `Role.PARENT` | new policy + `AttendanceService` | none | 4 h | Unit matrix: ACTIVE, EXPIRED, BASE/NONE, grace boundary, alias, deactivated |
| P2.2 | Split channels: in-app row for eligible accounts (D1 default), push gated separately; add routing metadata + `routingReason` to the notification metadata/audit | `AttendanceService`, `NotificationService` (small extension) | P2.1 | 3 h | Expired learner test: 0 push, in-app per D1 |
| P2.3 | Apply the same policy to the escalation sweep | `AttendanceService.escalateAbsentees` | P2.2 | 1 h | Expired learner's escalation produces no push/SMS |
| P2.4 | Tests: submit, mark-by-exception, escalation, alias, inactive account, grace, teacher/admin unaffected; full suite | server tests | P2.1–P2.3 | 4 h | New tests + all existing green |
| P2.5 | Record D1 result in docs and the F9/ledger residual line | docs | P2.4 | 30 min | Decision written, not implied |

### Phase 3 — Attendance SMS MVP, guarded fallback (~4 days incl. clients)

**Only start after D1/D2 and Phase 2.** Default off (`ATTENDANCE_SMS_ENABLED=false`); per-school allowlist.

| ID | Task | Repo/files | Depends | Est | Acceptance |
|---|---|---|---|---|---|
| P3.1 | Extract a shared `SmsGateway` client used by OTP and attendance (HostPinnacle/Beem/logging), keeping OTP behaviour unchanged | `auth/` + new `sms/` package | P1.4 | 4 h | OTP provider tests still pass; no behaviour change with flag off |
| P3.2 | V13 migration: `attendance_sms_dispatch` (unique `(attendance_record_id, recipient_user_id)`, phone, status, provider, provider_message_id, error, credits, timestamps) + per-school `sms_budget` (term KES + credits, used) + `user_settings.attendance_sms_opt_in` / consent timestamp | `db/migration`, entities, repos | P2 | 4 h | Migration from clean baseline; JPA validate passes |
| P3.3 | Policy engine (defaults): ABSENT only (never LATE/PRESENT), send after register close (default 10:30 school zone), cancel if corrected/read before send, guardian only, verified phone only, **one per phone per day**, sibling/alias dedupe, **only recipients with no active push token** (capability gating), opt-in/out per D2, quiet hours and school days | new `AttendanceSmsPolicy` | P3.2 | 6 h | Policy matrix tests: each rule independently asserted |
| P3.4 | Dispatch worker: idempotent outbox, one attempt, budget decrement transactionally, degrade to push/in-app at zero, never throws into register submit | scheduler/worker + service | P3.3 | 6 h | Replay/restart test: one SMS per record; exhausted budget sends nothing and alerts admin |
| P3.5 | Admin/report surface: per-school SMS ledger, spend/credits, budget get/put, delivery outcome; metadata for support | `attendance/web` or `console` API | P3.4 | 4 h | DTOs + tests; no secrets in payloads |
| P3.6 | Client opt-in: guardian toggle in notification settings + consent copy; Android and web; wire fixtures + tests | `BrainBox-master`, `BrainboxWeb-main` | P3.5 | 1.5 d | Toggle round-trips; default off/unchanged UI when flag off |
| P3.7 | Metrics/logging: sent, skipped reason, delivered (if DLR), cost credits, per school/day; no PII in logs | server | P3.4 | 2 h | Ops can answer "what did we spend and why" |
| P3.8 | Docs + cost report template | docs | P3.7 | 2 h | Plan status + runbook updated |

### Phase 4 — Cost refinement and delivery reliability (~2.5 days)

| ID | Task | Depends | Est | Acceptance |
|---|---|---|---|---|
| P4.1 | Read-ack fallback: for token holders, SMS only if unread after `ATTENDANCE_SMS_READ_ACK_MINUTES` (default 20) or push attempt failed | P3 | 1 d | Test: read before timer → no SMS; unread → one SMS |
| P4.2 | Digest for guardians with multiple children (one message lists all absent children) | P3.3 | 0.5 d | Sibling fixture: 2 children → 1 SMS |
| P4.3 | Risk threshold policy (optional, D3): only 2nd+ consecutive absence / term attendance below threshold for token holders | P3 | 0.5 d | Configurable; default off until measured |
| P4.4 | Provider fallback chain HostPinnacle → Beem/Africa's Talking on 4xx/5xx/timeout, circuit breaker, metrics | P1 | 1 d | Stub failure routes to second provider; no duplicate send |
| P4.5 | DLR webhook (optional): endpoint + persistence + surface in ledger | P3.5 | 1 d | Signed/allowlisted endpoint; idempotent |
| P4.6 | Final EN/SW templates, quiet-hours copy, consent/privacy note (DPA/ODPC) | P0.4 | 0.5 d | Strings shipped, under 160 GSM-7 |

### Phase 5 — Offline delivery fixes (~2 days; credential-independent)

| ID | Task | Repo | Depends | Est | Acceptance |
|---|---|---|---|---|---|
| P5.1 | Server escalation catch-up: sweep the last `ATTENDANCE_ESCALATION_CATCHUP_DAYS` (default 3) for ABSENT rows with no escalation row; keep idempotency | server | none | 3 h | Midnight-late register escalates once, next sweep does not duplicate |
| P5.2 | Android: refresh notifications on app resume and on connectivity regained; add notifications refresh to `SyncWorker` for both roles; update badge from unread count | Android | none | 1 d | Airplane-mode script: mark offline → reconnect → badge/feed update without opening Notifications |
| P5.3 | Android: after a pending attendance replay, refresh notifications so the same-device parent alert is replaced by the server row | Android | P5.2 | 2 h | Replay test shows no duplicate/stale alert |
| P5.4 | Web: refresh notification centre on visibility/focus and online event | web | none | 3 h | Test with mocked visibility change |
| P5.5 | Optional push delivery ledger + retry beyond FCM's own attempts (D6) | server | P1 | 1 d | Deferred unless audited delivery is required |

### Phase 6 — Hardening and rollout (~1.5 days)

| ID | Task | Depends | Est | Acceptance |
|---|---|---|---|---|
| P6.1 | Staging E2E with live HostPinnacle: OTP + attendance SMS to 3 networks; sender ID, timing, delivery; capture cost | P1, P3, P5 | 1 d | Evidence table; all three networks OK |
| P6.2 | Budget defaults per school/term + console visibility (D5) | P3.5 | 0.5 d | Admin can set/see budget; exhausted → no sends |
| P6.3 | Runbook: key rotation, credit exhaustion, provider outage, failed sends, rollback (flag off / provider swap) | P1–P4 | 0.5 d | Runbook in docs; rollback tested |
| P6.4 | Final doc sweep (this plan, phone_verification.md, AGENTS.md, F9 audit, ledger) | all | 0.5 d | No stale "AT primary"/"no SMS" claims |

**Total: ~11–13 focused days**, of which ~5–6 are credential-independent (Phases 2 and 5). Phase 3 client work is the
long pole.

## 5. Subscription routing policy — specification

```
eligibleForAttendanceChannels(user, learner):
  user.isActive && user.accountKind == FULL
  && learner.subscription.status == ACTIVE
  && learner.subscription.tier != BASE
  && learner.subscription.expiryDate > now - graceDays
```

- In-app row: written for eligible accounts (D1 default; the alternative is "write for everyone, gate only push/SMS" —
  safer for historical visibility but gives the appearance of a paid feature for free).
- Push: eligible AND `shouldPush` (existing urgency rules; urgent absence still bypasses the push preference).
- SMS: eligible AND consent/capability/budget rules in §6.
- Legacy `PARENT` aliases: inherit the learner's eligibility. Do not grant eligibility from `Role.PARENT`.
- Deactivated accounts: never notified on any channel.
- Teachers/admins: attendance notifications are not addressed to them today; no change.
- Subscription reminders (existing `SubscriptionReminderScheduler`) remain the nudge for expired families.

## 6. Attendance SMS policy — specification

| Rule | Default | Why |
|---|---|---|
| Status filter | ABSENT only | LATE/PRESENT do not justify paid SMS |
| Timing | after register close (10:30 school zone) | lets corrections/late arrivals cancel the SMS |
| Cancel | if corrected to PRESENT/LATE/EXCUSED or push read before dispatch | avoids paying for resolved events |
| Audience | guardian/learner account only (F9 single row) | learner gets push free; no SMS duplication |
| Capability gating | only recipients with **no active push token** | the single biggest cost lever (~60–90%) |
| Subscription | Phase 2 eligibility | no paid channel for unpaid accounts |
| Phone | verified number only | unverified numbers waste credits |
| Consent | opt-in/out per D2; default off | DPA/ODPC + trust |
| Dedupe | one SMS per phone per day; siblings in one message; unique dispatch row per record | exactly-once on replay |
| Budget | per school per term (D5), global cap, degrade to push at zero | prevents runaway spend |
| Retries | single attempt; no automatic resend | duplicates cost money |
| Message | ≤160 GSM-7 chars, no emoji/accents/links, EN + SW | avoids 2-segment billing |
| Window | school days, 08:00–17:00 school zone | nuisance avoidance; not cost |

Cost model to validate in P0.3 (500 learners, 200 days, 5% absence, 60% token coverage, one SMS/event):
~5,000 absence events → ~1,500–2,000 SMS/year for the capability-gated audience. Target instrumentation: **≤400 SMS
per 1,000 absence events**, measured per school per month.

## 7. Configuration reference (new keys)

| Env var | Default | Purpose |
|---|---|---|
| `HOSTPINNACLE_API_KEY` | — | API key header |
| `HOSTPINNACLE_USERNAME` / `HOSTPINNACLE_PASSWORD` | — | Portal credentials (API user preferred) |
| `HOSTPINNACLE_SENDER_ID` | — | Approved sender ID |
| `HOSTPINNACLE_BASE_URL` | `https://smsportal.hostpinnacle.co.ke/SMSApi` | Portal base URL |
| `PHONE_VERIFICATION_PROVIDER` | `hostpinnacle` (flag remains off) | OTP provider |
| `ATTENDANCE_SMS_ENABLED` | `false` | Master switch |
| `ATTENDANCE_SMS_PROVIDER` | inherits phone provider | Delivery gateway |
| `ATTENDANCE_SMS_SEND_AFTER` | `10:30` | Register-close cutoff |
| `ATTENDANCE_ROUTING_GRACE_DAYS` | `3` | Subscription expiry grace |
| `ATTENDANCE_ESCALATION_CATCHUP_DAYS` | `3` | Late-sync escalation window |
| `ATTENDANCE_SMS_BUDGET_DEFAULT_KES` | decision D5 | Per school, per term |
| `ATTENDANCE_SMS_DEFAULT_OPT_IN` | `false` | Consent default |
| `ATTENDANCE_SMS_READ_ACK_MINUTES` | `20` | Phase 4 fallback |
| `ATTENDANCE_SMS_QUIET_START/END` | `08:00`/`17:00` | Send window |

Secrets are env-only, never logged, never committed. `ATTENDANCE_SMS_*` names are indicative and finalised in P3.2.

## 8. Metrics to instrument

| Metric | Source | Target/use |
|---|---|---|
| Absence events per school/day | attendance rows | denominator for cost |
| % guardians with active push token | push tokens | capability-gating yield |
| SMS per 1,000 absence events | dispatch ledger | ≤400 |
| Skip reasons (no token, read, corrected, budget, ineligible) | dispatch ledger | tuning |
| Delivery outcome + latency per network | provider/DLR | reliability |
| Budget used / remaining per school | budget table | ops |
| Notification read rate after push | notification rows | read-ack threshold tuning |

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| HostPinnacle API/response contract differs from the public client | P1.4 pins real bodies before code depends on them; failure → 503, never a silent success |
| Sender ID not approved for all networks/transactional traffic | P0.1 confirms; P1.6 tests 3 networks before enabling |
| Password must travel in the request body | HTTPS only, env-only, dedicated API user, no request logging |
| Credits exhausted → silent OTP failure | P1.8 balance probe + ops alert; OTP endpoint already returns an honest 503 when delivery fails |
| Subscription gating cuts absence alerts for families in arrears (safety) | D1: in-app always + grace window + renewal reminder; school can override for a learner |
| SMS becomes a nuisance / DPA complaint | opt-in, quiet hours, one per day, verified numbers, consent copy |
| Push-token gating sends no SMS to a guardian whose app is broken | Phase 4 read-ack/fallback covers token holders; Phase 5 makes missed pushes visible in-app |
| Budget configured too low, no alerts reach parents | degrade path + admin low-budget alert; measure first (P0.3) |
| Refactor of the OTP notifier regresses phone verification | P3.1 keeps OTP tests untouched and green |

## 10. Open decisions (owner)

| ID | Question | Recommendation |
|---|---|---|
| D1 | In-app rows for expired learners, or gate everything? | keep in-app, gate push/SMS, 3-day grace |
| D2 | SMS consent model: opt-in vs opt-out for no-app guardians | opt-in at first use + fallback-only automatic; never bulk opt-in silently |
| D3 | Risk threshold (2nd+ absence) for token holders | defer until P0.3 data; default off |
| D4 | Weekly digest for chronic absentees | defer; one-per-day + sibling merge first |
| D5 | Per-school termly SMS budget default | ask the owner; start ~KES 2,000/term/school for a 500-learner pilot |
| D6 | Push delivery proof required? | no for MVP; DLR webhook optional in Phase 4 |
| D7 | Who pays for attendance SMS: BrainBox or the school? | school budget; makes the cost visible and capped |

## 11. What the owner must supply (Phase 0/1)

1. HostPinnacle **base URL** confirmation (`https://smsportal.hostpinnacle.co.ke/SMSApi`).
2. **API key** (portal API/developer page).
3. **Portal username + password** (dedicated API user preferred).
4. **Approved sender ID** string, working on Safaricom/Airtel/Telkom, transactional allowed.
5. **Credit/pricing facts**: what 250 credits buys, per-SMS price, segment billing, whether API sends draw the same balance.
6. **Restrictions**: IP allowlist (provide our egress IP if yes), rate limits, sandbox/test mode, sending windows.
7. **Test numbers**: one per network; permission to spend ~10 credits on live tests.
8. **API sample**: portal request/response screenshot or doc (pins success/failure parsing).
9. **Fallback choice**: keep AT/Beem code (recommended) or remove.
10. **Decision D1/D2/D5** so Phase 2/3 defaults are not guessed.

Secrets go in a git-ignored file (e.g. `~/brainbox-secrets/hostpinnacle.env`) or the shell env, not in the repo and
not in the chat.

## 12. Verification and definition of done

- **Server**: focused tests per phase + full `./gradlew test` green (717+ tests, 3 skipped baseline). Migration applies
  on the collapsed baseline under `ddl-auto: validate`.
- **Android**: `./gradlew :app:testDevDebugUnitTest --offline` green; wire fixtures updated for any new payload.
- **Web**: `npm test` green (92 files / 1,344 tests baseline).
- **Live**: one OTP and one absence SMS delivered on each of the three networks; evidence captured in this doc.
- **Cost**: one week of staging data shows SMS per 1,000 absence events ≤ target and zero duplicate sends.
- **Rollback**: flag off restores the current push-only behaviour; provider swap is env-only.
- **Docs**: this plan, `phone_verification.md`, `AGENTS.md`, F9 audit and the ledger all agree.

## 13. Status tracker

| Phase | Status | Notes |
|---|---|---|
| 0 Inputs/decisions | waiting on owner | credentials + D1/D2/D5 |
| 1 HostPinnacle default | not started | can scaffold before credentials |
| 2 Subscription routing | not started | credential-independent |
| 3 Attendance SMS MVP | not started | needs D1/D2, Phase 2 |
| 4 Cost refinements | not started | needs Phase 3 measurements |
| 5 Offline fixes | not started | credential-independent |
| 6 Hardening/rollout | not started | needs live provider |

---

*No source, test or migration files were changed when this plan was written. The only edits are this document and the
two stale provider references it corrects.*
