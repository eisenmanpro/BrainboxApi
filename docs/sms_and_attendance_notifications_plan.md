# SMS provider, attendance notifications and offline delivery — phased implementation plan

**Status: PHASE 3 IMPLEMENTED; live delivery blocked on the sender ID.** Written 2026-11-12 before the
HostPinnacle credentials arrived, then updated after Phase 1 (default provider), Phase 2 (subscription-aware
routing), Phase 5.1–5.4 (offline catch-up) and Phase 3 (the guarded attendance SMS channel) landed. Phase 0
decisions are locked (D1/D2/D5 answered, §10); Phases 1–3 and 5 are implemented and tested; Phase 1.6/6.1 live
smoke waits on a purchased HostPinnacle sender ID. Phase 4 is now unblocked (it consumes P3 measurements), then
Phase 6. The document turns the research in [phone_verification.md](phone_verification.md) and the
attendance/offline audit into an ordered, testable task list.

| | |
|---|---|
| **Repos** | `BrainboxApi-master` (primary), `BrainBox-master` (Android), `BrainboxWeb-main` (web) |
| **Related records** | [phone_verification.md](phone_verification.md) (provider research, OTP slice), [account_unification.md](account_unification.md) (F9: learner account is the guardian), `.design-review/f9-account-unification-audit.md`, `.design-review/audit-rubric-and-backlog.md` (Android repo) |
| **Decision supersedes** | "Africa's Talking primary, Beem fallback" in `phone_verification.md` — HostPinnacle becomes the default; AT/Beem stay as config-selectable fallback |
| **Blocked on** | HostPinnacle **sender ID purchase** (support confirms there are no test sender IDs) for the Phase 1 live smoke only; Africa's Talking sandbox is available for dev delivery tests and was verified live. Credentials, pricing, test numbers and D1/D2/D5 are in hand. |

Phase 1's notifier was built and tested against a local HTTP stub plus the provider's real error bodies. Phase 2
(subscription routing) and Phase 5.1–5.4 (offline fixes) are implemented, tested and recorded below; they were
credential-independent, so they ran before the sender-ID purchase.

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
   in-app history always, push/SMS only for learners whose subscription is ACTIVE. Owner decision recorded 2026-11-12:
   **free channels (in-app + push) for every non-deactivated account, SMS only for PRO/ACTIVE subscribers** ("users
   should get what their subscription allows"; "push and in-app to explorer accounts").

### Owner decisions recorded 2026-11-12

- **D1 (subscription targeting):** "if the server has no way to target subscribers then the whole subscription model
  fails; users should get what their subscription allows." Schema changes are allowed — the production database has
  never held real users, will be reset to its first version at launch, and real data collection starts in the
  post-launch week. Concrete channel matrix: **explorer/expired → in-app + push only; PRO/ACTIVE non-BASE → in-app +
  push + SMS** (SMS still subject to consent, capability and budget gates). Deactivated accounts are not subscribers
  and are not notified.
- **D2 (SMS consent):** opt-in at first use, **fallback-only automatic**; never bulk opt-in silently.
- **D5 (school SMS budget):** KES 2,000 per school per term, visible in the HOI / ICT-admin panels; the server
  tracks which schools have paid and shows their balance with a neutral description only (no "optional"/"support"
  wording). The gating prefers PRO users for SMS while push/in-app stay free to explorer accounts. A school's payment
  status must not block the subscription-driven sends ("this is support from schools to keep the system working");
  the term allocation is drawn down by SMS and, when exhausted, SMS stops while push/in-app continue and the
  admin/HOI sees the low-balance state.
- Still open and deliberately deferred: D3 (risk threshold), D4 (weekly digest), D6 (push delivery proof).

## 2. Current state — evidence

### 2.1 Phone verification (shipped, dormant)

Already implemented, tested and committed: V12 (`phone_verification_challenges` + `users.phone_verified_at`),
authenticated request/confirm/status endpoints, hashed 6-digit code, TTL/attempts/cooldown/rate limits, provider seam
`PhoneVerificationNotifier` with `AfricasTalkingSmsNotifier` and `BeemSmsNotifier`, and the Android/web cards.
Dormant behind `app.auth.phone-verification.enabled=false` ([application.yml](../src/main/resources/application.yml:176)).
The HostPinnacle provider is now also implemented (properties block, `HostpinnacleSmsNotifier`, form-wire tests), so the
only missing piece for a live OTP is the purchased HostPinnacle sender ID; Africa's Talking's sandbox is the interim
delivery test channel.

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
| FCM push | **yes — free channel (D1 owner decision)** | yes (subject to push settings) | n/a |
| Attendance SMS | no | only if consent + capability gates pass | n/a |
| Subscription renewal nudge | existing reminder service | existing reminder service | n/a |

Legacy `PARENT` rows bypass subscriptions by role; under F9 they are aliases, so eligibility must be inherited from the
learner they alias, not from the role. A grace window after expiry (`ATTENDANCE_ROUTING_GRACE_DAYS`, default 3) keeps
alerts flowing for a few days after a payment lapse; this is part of D1 and is implemented in
`AttendanceRecipientPolicy`.

### 2.4 Offline delivery

| Scenario | Current behaviour | Gap |
|---|---|---|
| Teacher offline (Android) | Register cached in Room as pending, replayed by the 30-minute WorkManager periodic sync ([SyncManager.kt:16](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/data/sync/SyncManager.kt:16), [SyncWorker.kt:61](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/data/sync/workers/SyncWorker.kt:61), [TeacherAttendanceRepository.kt:146](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/teacher/data/repository/TeacherAttendanceRepository.kt:146)) | Cross-device fan-out waits for connectivity; replay can take up to 30 min; the pending replay does not refresh notifications afterwards |
| Teacher offline (web) | Register goes to the web write outbox with retries | Same, no local fallback alert |
| Parent phone offline | In-app row persists server-side; FCM queues high-priority messages | A missed/expired push gives no badge or feed update until the Notifications screen is opened; `SyncWorker` never refreshes notifications and `NotificationsViewModel` refreshes only in `init` ([NotificationsViewModel.kt:43](../../BrainBox-master/app/src/main/java/com/afrithecus/brainbox/viewmodels/NotificationsViewModel.kt:43)) |
| FCM/provider down | Row persists; sender retries transient errors; fan-out records a metric | No delivery ledger/proof; no SMS fallback |
| Escalation + late sync | Sweep only looks at *today* | A register synced after midnight never escalates (the absence notice is lost; the record itself is fine) |
| Web parent/learner | Notification centre refreshes when opened; cached offline | No real-time web notifications at all |

### 2.5 Provider probe evidence (2026-11-12, owner credentials)

- **HostPinnacle, real host, empty sender id:** HTTP 200 with
  `{"status":"error","statusCode":"162","reason":"Empty senderId"}` — HTTP status alone is not success; the body's
  `status` field is the contract.
- **HostPinnacle, invalid sender id:** HTTP 200 with
  `{"status":"error","statusCode":"203","reason":"Given SenderId is invalid. Please use valid SenderId."}`.
- **HostPinnacle, GET instead of POST:** HTTP 200 with `status=error | errorCode=152 | reason=Invalid method` (plain
  text, another reason the notifier must fail closed on an unrecognised body).
- **HostPinnacle success contract:** the official client's own tests fake success as `{"status":"success"}`; the
  notifier classifies success only on that explicit status.
- **Africa's Talking sandbox:** a real send to `+254112887706` returned HTTP 201 and
  `{"SMSMessageData":{"Message":"Sent to 1/1 Total Cost: KES 0.8000 Message parts: 1","Recipients":[{"status":"Success","statusCode":101}]}}`
  — usable for end-to-end OTP delivery tests before the HostPinnacle sender ID is approved.
- **Consequence:** HostPinnacle is wired and unit-tested but cannot deliver until the owner buys a sender ID in the
  portal (support: no test sender IDs). Live smoke stays blocked; everything else in Phase 1 is done.

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
| P0.1 | Owner supplies HostPinnacle credentials, sender ID, API sample, pricing/credit facts and test numbers (§11) | owner | — | **Done 2026-11-12 except the sender ID:** API key, portal username/password, pricing and two test numbers supplied; sender ID must be purchased (no test sender IDs); AT sandbox verified live; HostPinnacle probes captured |
| P0.2 | Record D1–D7 decisions (§10) in this doc | owner | 0.5 h | **Done:** D1/D2/D5 answered by the owner (§1); D3/D4/D6 deferred |
| P0.3 | Baseline measurement on staging/prod read-only: absence events/day, % guardians with a push token, sibling overlap, pre-cutoff correction rate, notification read rate | none | 2 h | Numbers table appended to §8; these drive thresholds |
| P0.4 | Agree SMS templates (EN + SW, ≤160 GSM-7 chars) and consent copy | owner | 0.5 h | Strings in this doc |

### Phase 1 — HostPinnacle as the default provider + OTP activation (~1 day)

**Status 2026-11-12:** P1.1, P1.2, P1.3, P1.5 and P1.7 done; P1.4 pinned against the real error bodies and the official
client's success contract (a live 2xx success send is still impossible without the sender ID); P1.6 blocked on the
sender ID purchase; P1.8 (credit probe) not started.

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

**Status 2026-11-12 (afternoon): P2.1–P2.5 done.** `AttendanceRecipientPolicy` implements the D1 matrix,
`AttendanceService` applies it to both the mark fan-out and the escalation sweep, `NotificationService.notifyUser`
gained `pushEligible` so an ineligible account still gets its in-app row but no push, and every delivered row now
carries `routingReason` + `smsEligible` metadata. Verified by `AttendanceRecipientPolicyTest` (13 cases: ACTIVE,
grace boundary, expired, stale ACTIVE, BASE/NONE, no row, deactivated/ROSTER_ONLY recipient, deactivated learner,
alias inheritance, PARENT-role non-inheritance) and five new `AttendanceWebTests` integration cases; the existing
attendance suites stay green.

| ID | Task | Repo/files | Depends | Est | Acceptance |
|---|---|---|---|---|---|
| P2.1 | `AttendanceRecipientPolicy`: eligibility = user exists, `isActive`, `accountKind=FULL`, learner subscription `ACTIVE`, tier ≠ `BASE`, expiry > now (+ `ATTENDANCE_ROUTING_GRACE_DAYS`); legacy parent alias inherits from the learner, not from `Role.PARENT` | new policy + `AttendanceService` | none | 4 h | Unit matrix: ACTIVE, EXPIRED, BASE/NONE, grace boundary, alias, deactivated |
| P2.2 | Split channels: in-app row for eligible accounts (D1 default), push gated separately; add routing metadata + `routingReason` to the notification metadata/audit | `AttendanceService`, `NotificationService` (small extension) | P2.1 | 3 h | Free channels (in-app + push) reach every non-deactivated account, so an expired learner still gets both; the row's metadata records `routingReason=FREE_CHANNELS_ONLY`, `smsEligible=false` (D1 supersedes the earlier "0 push" draft) |
| P2.3 | Apply the same policy to the escalation sweep | `AttendanceService.escalateAbsentees` | P2.2 | 1 h | Expired learner's escalation produces no push/SMS |
| P2.4 | Tests: submit, mark-by-exception, escalation, alias, inactive account, grace, teacher/admin unaffected; full suite | server tests | P2.1–P2.3 | 4 h | New tests + all existing green |
| P2.5 | Record D1 result in docs and the F9/ledger residual line | docs | P2.4 | 30 min | Decision written, not implied |

### Phase 3 — Attendance SMS MVP, guarded fallback (~4 days incl. clients)

**Status 2026-11-12: P3.1–P3.5, P3.7 and P3.8 done; P3.6 Android done, web deferred (no guardian settings
surface exists in the web app — it is a teacher/admin console, so there is no place to opt a guardian in; the
server contract is already exposed for a future parent portal).** See §14 for the implementation notes, the
config keys, the skip reasons and the cost report template. Default off (`ATTENDANCE_SMS_ENABLED=false`);
per-school allowlist.

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

**Status 2026-11-12 (afternoon): P5.1–P5.4 done; P5.5 (delivery ledger) deferred per D6.** The escalation sweep
now repairs the last `ATTENDANCE_ESCALATION_CATCHUP_DAYS` (default 3) days, keeping the unique escalation row as
the idempotency guard. Android refreshes the feed on start/resume/connectivity (one `NotificationRefresh` helper),
in `SyncWorker` for both roles, and after a replay that actually synced pending registers; the badge updates from
the same Room flow. Web refreshes `NotificationsCenter` on visibility/focus/online with per-mount listener cleanup.
Verified by a new catch-up integration test, `NotificationRefreshTest`, and `notificationsCenter.test.mjs`.

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

## 5. Subscription routing policy — specification (D1 locked)

```
freeChannelsEligible(user, learner):
  user.isActive && user.accountKind == FULL          // deactivated accounts: nothing
                                                     // explorer/EXPIRED/BASE: yes (in-app + push)

paidChannelEligible(user, learner):
  freeChannelsEligible(user, learner)
  && learner.subscription.status == ACTIVE
  && learner.subscription.tier != BASE
  && learner.subscription.expiryDate > now - graceDays   // graceDays default 3
```

- **In-app row + FCM push: every non-deactivated account, including explorer/expired**, subject to the existing
  `shouldPush` rules (urgent absence still bypasses the push preference). Owner decision 2026-11-12.
- **SMS: `paidChannelEligible` only**, then the §6 consent/capability/budget rules. A paid SMS is never spent on a
  family whose subscription does not include it.
- Legacy `PARENT` aliases: inherit the learner's eligibility for both matrices. Do not grant eligibility from
  `Role.PARENT`.
- Deactivated accounts: no in-app, push or SMS.
- Teachers/admins: attendance notifications are not addressed to them today; no change.
- Subscription reminders (existing `SubscriptionReminderScheduler`) remain the nudge and renewal path for expired
  families; the free channels keep them informed meanwhile.

**Implemented 2026-11-12 (P2.1–P2.5).** `AttendanceRecipientPolicy.decide(learner, recipient)` returns the three
channel booleans plus a `RoutingReason`; the expiry is authoritative (a stale ACTIVE row with a past expiry is
lapsed) and the 3-day grace window is exclusive at its boundary. A legacy PARENT alias inherits the learner's
eligibility — `Role.PARENT` alone never grants the paid channel — and a deactivated or ROSTER_ONLY learner silences
every recipient. `NotificationService.notifyUser` now takes `pushEligible` (default true, so every other caller is
unchanged): the in-app row is still written, the push is suppressed only when the policy denies it. Both fan-out
paths write `routingReason` and `smsEligible` into the row metadata. `ATTENDANCE_ROUTING_GRACE_DAYS` (default 3) is
the only new configuration.

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

Owner pricing fact (2026-11-12): **KES 0.20 per SMS unit** — packs are 250 units KES 50, 1,000 = KES 200,
2,500 = KES 500, 5,000 = KES 1,000 (the cheapest route researched by a wide margin). Cost model to validate in P0.3
(500 learners, 200 days/year, 5% absence, 60% token coverage, one SMS/event): ~5,000 absence events/year → ~1,500–2,000
SMS/year for the capability-gated PRO audience ≈ **KES 300–400/year**, i.e. ~KES 100–130 per term. The D5 school
allocation of KES 2,000/term therefore buys ~10,000 SMS — two orders of magnitude of headroom for a 500-learner school.
Target instrumentation: **≤400 SMS per 1,000 absence events**, measured per school per month.

## 7. Configuration reference (new keys)

| Env var | Default | Purpose |
|---|---|---|
| `HOSTPINNACLE_API_KEY` | — | API key header |
| `HOSTPINNACLE_USERNAME` / `HOSTPINNACLE_PASSWORD` | — | Portal credentials (API user preferred) |
| `HOSTPINNACLE_SENDER_ID` | — | Approved sender ID |
| `HOSTPINNACLE_BASE_URL` | `https://smsportal.hostpinnacle.co.ke/SMSApi` | Portal base URL |
| `PHONE_VERIFICATION_PROVIDER` | `hostpinnacle` (flag remains off) | OTP provider |
| `ATTENDANCE_SMS_ENABLED` | `false` | Master switch; off removes the scheduler bean entirely |
| `ATTENDANCE_SMS_PROVIDER` | inherits phone provider | Delivery gateway (hostpinnacle/africastalking/beem/logging) |
| `ATTENDANCE_SMS_SEND_AFTER` | `10:30` | Register-close cutoff in the school zone |
| `ATTENDANCE_SMS_WINDOW_START` / `ATTENDANCE_SMS_WINDOW_END` | `08:00` / `17:00` | Nuisance window; outside it nothing is sent |
| `ATTENDANCE_SMS_SCHOOL_DAYS` | Mon–Fri | School-day list (weekend registers never buy an SMS) |
| `ATTENDANCE_SMS_SCHOOL_ALLOWLIST` | empty = all schools | Comma-separated school UUIDs for a pilot/staging gate |
| `ATTENDANCE_SMS_BUDGET_DEFAULT_KES` | `2000` | Per school, per term allocation (D5) |
| `ATTENDANCE_SMS_COST_PER_SMS_KES` | `0.20` | Price used for budget draw-down and the ledger |
| `ATTENDANCE_SMS_LOW_BALANCE_THRESHOLD_KES` | `20` | Remaining balance that triggers the one ICT-admin alert |
| `ATTENDANCE_SMS_DEFAULT_OPT_IN` | `false` | Consent default (D2) |
| `ATTENDANCE_SMS_REQUIRE_VERIFIED_PHONE` | `true` | Paid channel requires `users.phone_verified_at` |
| `ATTENDANCE_SMS_MESSAGE_TEMPLATE` | `BRAINBOX: {learner} was marked absent on {date}. ...` | Plain GSM-7 template, `{learner}`/`{date}`/`{school}` |
| `ATTENDANCE_SMS_SCAN_INITIAL_DELAY_MS` / `ATTENDANCE_SMS_SCAN_MS` | `600000` / `600000` | Sweep schedule |
| `ATTENDANCE_ROUTING_GRACE_DAYS` | `3` | Subscription expiry grace (D1) |
| `ATTENDANCE_ESCALATION_CATCHUP_DAYS` | `3` | Late-sync escalation window (P5.1) |
| `ATTENDANCE_SMS_READ_ACK_MINUTES` | `20` | Phase 4 fallback, not implemented yet |

Secrets are env-only, never logged, never committed. All keys are implemented in
`AttendanceSmsProperties` / `application.yml`.

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

## 10. Decisions

### Locked by the owner (2026-11-12)

| ID | Decision |
|---|---|
| D1 | Subscription targeting is real: **in-app + push for every non-deactivated account; SMS only for PRO/ACTIVE non-BASE learners** (+ expiry grace, default 3 days). Schema changes are allowed; the database resets to its first version at launch and real data gathering starts post-launch week. |
| D2 | **Opt-in at first use + fallback-only automatic; never bulk opt-in silently.** |
| D5 | **KES 2,000 per school per term**, visible in the HOI / ICT-admin panels; the server tracks paying schools and their balance and shows a neutral description only. PRO is the SMS gate; school payment status never blocks subscription-driven sends; SMS draw down the term allocation and stop when exhausted while push/in-app continue. |
| D7 | Attendance SMS is funded through the school term allocation (D5); BrainBox still sends according to subscription entitlement. |

### Still open (deferred until the Phase 3/4 measurements)

| ID | Question | Current default |
|---|---|---|
| D3 | Risk threshold (2nd+ absence) for token holders | off until P0.3/P4 measurements |
| D4 | Weekly digest for chronic absentees | one-per-day + sibling merge first |
| D6 | Push delivery proof required? | no for MVP; DLR webhook optional in Phase 4 |

## 11. Owner inputs — status (2026-11-12)

| # | Input | Status |
|---|---|---|
| 1 | HostPinnacle base URL | **Confirmed:** `https://smsportal.hostpinnacle.co.ke/SMSApi` (notifier appends `/send`) |
| 2 | API key | **Supplied** in the git-ignored secrets file |
| 3 | Portal username + password | **Supplied** in the secrets file (dedicated API user still recommended) |
| 4 | Approved sender ID | **Outstanding — must be purchased in the portal.** Support confirms no test sender IDs. Blocks live HostPinnacle sends and the Phase 1.6 smoke only. |
| 5 | Pricing/credit facts | **Supplied:** KES 0.20/unit; 250 units KES 50; 1,000 = KES 200; 2,500 = KES 500; 5,000 = KES 1,000; API sends draw the same balance |
| 6 | Restrictions | No IP allowlist/sandbox reported; single send attempts and the provider's own limits apply; confirm at first real send |
| 7 | Test numbers | **Supplied:** 254112887706 and 254758778626; permission for ~10 live tests |
| 8 | API sample | **Captured live:** empty senderId = statusCode 162; invalid senderId = 203; GET = errorCode 152; success = `{"status":"success"}` (from the official client) |
| 9 | Fallback choice | **Keep Africa's Talking and Beem** as config-selectable fallbacks; nothing deleted |
| 10 | D1/D2/D5 | **Answered** (§10); D3/D4/D6 deferred |

**Secrets rule:** the real values live in `/home/deadman/brainbox-secrets/hostpinnacle.env` (outside every repo) and
are never copied into the repository, a commit, a log line or a document. The file maps to env names as:
`API_KEY→HOSTPINNACLE_API_KEY`, `USERNAME→HOSTPINNACLE_USERNAME`, `PASSWORD→HOSTPINNACLE_PASSWORD`,
`SENDER_ID→HOSTPINNACLE_SENDER_ID`, `API_KEY_AFRICATALKING→AFRICASTALKING_API_KEY` (sandbox username `sandbox`).

**Interim live testing:** Africa's Talking sandbox (username `sandbox`, the supplied sandbox key,
`AFRICASTALKING_BASE_URL=https://api.sandbox.africastalking.com/version1/messaging`) delivered a real SMS to
`+254112887706` during this session (HTTP 201, status Success). It is only a dev/test channel — production remains
HostPinnacle once its sender ID is approved.

## 12. Verification and definition of done

**Phase 3 run log — 2026-11-12:** **full server suite 759 tests, 0 failures, 3 skipped** (40m 40s; adds
`AttendanceSmsPolicyTest` 13 rule cases and `AttendanceSmsDispatchTests` 7 integration cases — exactly-once
replay, budget draw-down, submission-time gates, sibling dedupe, exhausted-budget alert, opt-in round-trip,
console/school endpoints). Focused phone-verification, attendance, profile and new SMS suites green.
**Android prod unit tests 387/0** (the new fields are defaults, so no fixture churn); **web 93 files / 1,346
tests, 0 failures** (no web code change — the guardian surface does not exist there).

**Phase 2/5 run log — 2026-11-12:** full server suite **739 tests, 0 failures, 3 skipped** (44m 25s; includes
`AttendanceRecipientPolicyTest` 13 and the five new `AttendanceWebTests` cases); Android prod-flavor unit tests
**387/0** (adds `NotificationRefreshTest` 3); web **93 files / 1,346 tests, 0 failures** (adds
`notificationsCenter.test.mjs` 2). Note: `testDevDebugUnitTest` cannot build on this machine right now because
the owner's local `app/google-services.json` was replaced with a prod-only client list; that is a local Firebase
configuration, not a source change, and the prod variant exercises the same unit-test source set.

- **Server**: focused tests per phase + full `./gradlew test` green (759 tests, 0 failures, 3 skipped after
  Phase 3). Migration V13 applies on the collapsed baseline under `ddl-auto: validate`.
- **Android**: `./gradlew :app:testProdDebugUnitTest --offline` green while the local Firebase file has no dev
  client; on a machine with the dev client `testDevDebugUnitTest` is the canonical command. Wire fixtures updated
  for any new payload.
- **Web**: `npm test` green (93 files / 1,346 tests after this phase).
- **Live**: one OTP and one absence SMS delivered on each of the three networks; evidence captured in this doc.
- **Cost**: one week of staging data shows SMS per 1,000 absence events ≤ target and zero duplicate sends.
- **Rollback**: flag off restores the current push-only behaviour; provider swap is env-only.
- **Docs**: this plan, `phone_verification.md`, `AGENTS.md`, F9 audit and the ledger all agree.

## 13. Status tracker

| Phase | Status | Notes |
|---|---|---|
| 0 Inputs/decisions | **done except sender ID** | D1/D2/D5 locked; credentials, pricing, test numbers and probe evidence in hand; P0.3 measurement waits for real post-launch data |
| 1 HostPinnacle default | **implemented, live smoke blocked** | notifier + properties + form-wire tests + docs landed; live send waits on the purchased sender ID; AT sandbox verified for OTP delivery tests |
| 2 Subscription routing | **done 2026-11-12** | `AttendanceRecipientPolicy` + push split + escalation policy; 13 unit cases + 5 integration cases green |
| 3 Attendance SMS MVP | **done 2026-11-12 (default off)** | shared gateway, V13, policy, worker, ledger, budget, console/school endpoints, Android opt-in; web has no guardian surface yet; live sends still wait on the sender ID |
| 4 Cost refinements | **next** | read-ack fallback, risk threshold, provider chain, DLR; P3 measurements now available |
| 5 Offline fixes | **done 2026-11-12 (P5.1–P5.4)** | catch-up sweep, Android resume/connectivity/sync/replay refresh, web visibility/focus/online; P5.5 deferred |
| 6 Hardening/rollout | not started | needs live provider |

---

## 14. Phase 3 implementation notes (P3.1–P3.8)

**Shared delivery seam (P3.1).** `com.afrithecus.brainbox.api.sms` now owns the wire contracts:
`SmsGateway` plus `HostpinnacleSmsGateway`, `AfricasTalkingSmsGateway`, `BeemSmsGateway` and
`LoggingSmsGateway`, built by `SmsGatewayFactory`. The three OTP notifiers delegate to the same classes, so
phone-verification behaviour is unchanged (its provider tests are untouched and green) and attendance can select a
different gateway with `ATTENDANCE_SMS_PROVIDER` without moving OTP.

**Schema (P3.2).** V13 is additive and dormant: `attendance_sms_dispatches` (unique
`(attendance_record_id, recipient_user_id)`, phone, status, provider/message id, error, units, cost, timestamps),
`school_sms_budgets` (unique `(school_id, term_label)`, budget/used KES, units, low-balance alert timestamp) and
`user_settings.attendance_sms_opt_in` + `attendance_sms_consent_at`. JPA validation passes on the collapsed
baseline.

**Policy (P3.3).** `AttendanceSmsPolicy.decide` is a pure decision table, asserted rule-by-rule in
`AttendanceSmsPolicyTest`. Order: master switch → school allowlist → ABSENT only → school day (default Mon–Fri) →
inside 08:00–17:00 school zone → after register close (10:30) → D1 paid-channel eligibility → valid Kenyan phone →
verified phone → D2 opt-in → no active push token. Skip reasons are named (`DISABLED`, `SCHOOL_NOT_ALLOWED`,
`STATUS_NOT_ABSENT`, `NOT_SCHOOL_DAY`, `BEFORE_SEND_AFTER`, `OUTSIDE_WINDOW`, `NOT_SUBSCRIBED`, `NO_PHONE`,
`UNVERIFIED_PHONE`, `NOT_OPTED_IN`, `HAS_PUSH_TOKEN`) and surfaced in debug logs. The message template is
plain GSM-7, sanitised and capped at 160 chars.

**Recipient choice.** The paid channel uses exactly one family inbox: a legacy PARENT alias when the learner has
one, otherwise the learner's own account (which under F9 IS the guardian account). It deliberately does not mirror
the free fan-out's recipient set, which would bill the same human twice.

**Worker (P3.4).** `AttendanceSmsDispatchService` scans today's ABSENT rows, reserves a dispatch row and draws
down the school's term budget in one transaction, calls the provider once outside the transaction (logging gateway
in tests), and finalises SENT/FAILED. The unique row makes reruns and restarts exactly-once; a corrected row and
the per-phone/day check cancel a send; a failure stays FAILED (no retry). It is never called from register submit
and the scheduler swallows every failure.

**Budget (D5).** Terms are derived as Jan–Apr T1, May–Aug T2, Sep–Dec T3 (no term calendar exists yet); the label
is stored on the row. The default allocation is created lazily at KES 2,000; each send draws KES 0.20. At zero the
worker sends nothing, push/in-app continue, and one neutral in-app alert goes to the school's ICT admin (and any
school-scoped ADMIN) per term. A console top-up clears the alert flag.

**Surfaces (P3.5).** Platform console: `GET /admin/console/sms/schools`, `GET /admin/console/sms/schools/{id}`,
`POST /admin/console/sms/schools/{id}/budget`, `GET /admin/console/sms/schools/{id}/dispatches` (SCHOOLS_READ /
SCHOOLS_MANAGE). School panel: `GET /school/attendance-sms/summary` and `/dispatches` for the caller's own school
(ICT admin or platform admin). Payloads carry masked phones, decimal money strings, no credentials.

**Client opt-in (P3.6).** Android `UserSettings` gained `attendanceSmsEnabled`, `attendanceSmsOptIn` and
`attendanceSmsConsentAt` (defaults false/null, so an older server leaves the UI unchanged); the notification card
shows the toggle only when the server enables the channel and confirms consent in an `AlertDialog` before
PATCHing `attendanceSmsOptIn`. The web app is a teacher/admin console with no guardian settings surface, so the
web toggle is deferred; the API contract is already in place for a future parent portal.

**Logging and cost report (P3.7/P3.8).** Sent attempts log dispatch id, school id, provider and provider message id;
failures log the provider reason without a phone number; skips log at debug with the reason. No phone number or
credential is logged. Cost report template (per school per term, fill from the console):
`school | term | budget KES | used KES | SMS sent | SMS failed | per 1,000 absence events | low-balance?`.

**Residuals.** Live delivery still needs the purchased HostPinnacle sender ID (P1.6/P6.1); Phase 4 owns read-ack
fallback, the optional risk threshold, provider fallback chain and DLR/delivery proof; the push delivery ledger
remains deferred (D6). Web opt-in waits for a guardian surface. One product boundary to state plainly: under F9 the
paid channel can only reach a FULL account with a phone, so a family with no smartphone (a ROSTER_ONLY learner) is
not an attendance subject and cannot be reached by SMS yet; reaching those families needs a school-level
guardian-phone channel outside the account model, which is a separate product decision for Phase 4/6.

---

*Plan first written with no source changes. Phase 1 has since landed code: `PhoneVerificationProperties.Hostpinnacle`,
`HostpinnacleSmsNotifier`, the `app.auth.phone-verification.hostpinnacle` config block, provider wire tests and
`PhoneNumbersTest`; `phone_verification.md` and this plan were updated with the real provider evidence and locked
decisions. No migration or client change was needed.*
