# Attendance SMS + phone verification - staging simulation and production rollout runbook

**Phase 6 (P6.0/P6.3) of [sms_and_attendance_notifications_plan.md](sms_and_attendance_notifications_plan.md).**
The channel is implemented and **dormant by default**. This runbook turns the sender-ID arrival into a
configuration exercise: staging can exercise every path without the provider, and production enablement is an
ordered checklist with an emergency brake at each step.

| | |
|---|---|
| **Audience** | Platform operator / on-call engineer enabling OTP and attendance SMS |
| **Scope** | HostPinnacle primary (Africa's Talking/Beem fallback), phone-verification OTP, guarded attendance SMS |
| **Not in scope** | App-store releases, Android/web code changes, provider account KYC |
| **Rule** | No secret is ever committed, logged, pasted into a ticket or shown in a screenshot |

---

## 1. Current state and what "done" means

- Phone verification and attendance SMS are behind `PHONE_VERIFICATION_ENABLED=false` and
  `ATTENDANCE_SMS_ENABLED=false`. Neither scheduler exists while the flags are off.
- The only hard external blocker is the **purchased HostPinnacle sender ID**. Credentials, pricing, test numbers
  and the D1/D2/D5 decisions are in hand.
- **Staging DLR simulation (P6.0) is implemented** so the ledger, delivery-report persistence, console and school
  panels can be verified before a real provider delivery report is ever observed. It is staging-only twice over:
  the controller/scheduler beans exist only under the `staging` profile, and the service refuses any call from
  another profile even if the property is set. Production has no route at all.
- **Definition of done:** all three networks receive an OTP and one real absence SMS from staging; the ledger shows
  provider, message id, cost and delivery state; the production canary school runs for 48 hours within the cost and
  failure targets; rollback has been exercised at least once in staging.

---

## 2. Prerequisites (owner / ops / engineering)

| # | Item | Where | Check |
|---|---|---|---|
| 1 | HostPinnacle **sender ID purchased and approved** (no test sender IDs exist) | HostPinnacle portal | Portal shows the sender ID; support confirms it is live on all three networks |
| 2 | API credentials (dedicated API user preferred) + portal password | secrets store, never the repo | `HOSTPINNACLE_API_KEY`, `HOSTPINNACLE_USERNAME`, `HOSTPINNACLE_PASSWORD` set |
| 3 | Sender ID propagated to the API environment | same secrets store | `HOSTPINNACLE_SENDER_ID` set |
| 4 | Provider credit sufficient for pilot + smoke | HostPinnacle portal | Balance covers at least 1,000 units (~KES 200) plus headroom |
| 5 | DLR callback decision: can HostPinnacle send a custom header? | HostPinnacle support | If **yes**, configure `X-SMS-DLR-Secret` and the callback URL. If **no**, leave the secret blank (webhook disabled, 404) and record DLR as unavailable |
| 6 | DLR shared secret generated per environment | `openssl rand -hex 32` | stored as `ATTENDANCE_SMS_DLR_SECRET`; never reused between staging and production |
| 7 | Staging and production DB + `SPRING_PROFILES_ACTIVE` | deployment | staging / prod respectively; never `staging` in production |
| 8 | Test numbers with consent, one per network (Safaricom, Airtel, Telkom) | owner | numbers can receive SMS and are documented (masked) in the evidence table |
| 9 | Platform operator account | console | ADMIN with `SCHOOLS_READ` + `SCHOOLS_MANAGE` |
| 10 | Pilot school UUID and one class with a teacher account | console / app | school allowlisted only for the canary |
| 11 | Test guardian/learner: FULL account, ACTIVE non-BASE subscription, **verified phone**, D2 opt-in, no push token (or push unread) | app | the paid policy gates every one of these |
| 12 | Access to logs + metrics of the API nodes | observability | metric names in section 7 are visible |
| 13 | Android staging build / web console pointed at the staging API | CI or local | a teacher can submit a register to staging |

---

## 3. Configuration matrix

All flags are read at startup. **A changed flag requires a rolling restart**; the only exception is the per-school
budget, which is a database write through the console.

| Variable | Staging | Production | Purpose |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `staging` | `prod` | `staging` loads the DLR simulator beans; `prod` never does |
| `PHONE_VERIFICATION_ENABLED` | `true` | `true` (step 2) | OTP endpoints and gateway active |
| `PHONE_VERIFICATION_PROVIDER` | `hostpinnacle` (or `logging` for a no-cost dry run) | `hostpinnacle` | OTP gateway |
| `PHONE_VERIFICATION_LOG_OTP` | `false` | `false` | never log a real code outside local development |
| `HOSTPINNACLE_*` | staging credentials + sender ID | production credentials + sender ID | wire contract |
| `AFRICASTALKING_*` / `BEEM_*` | only if testing fallback | only if fallback is approved | alternative gateway for OTP or attendance |
| `ATTENDANCE_SMS_ENABLED` | `true` for the smoke | `false` until step 3, then `true` | master switch; off removes the scheduler and policy refuses |
| `ATTENDANCE_SMS_PROVIDER` | `hostpinnacle` (or `logging`) | `hostpinnacle` | attendance-only gateway, inherits OTP provider when blank |
| `ATTENDANCE_SMS_SCHOOL_ALLOWLIST` | pilot school UUID | canary school UUID, then expand | non-empty is the pilot gate; empty means every school |
| `ATTENDANCE_SMS_DLR_SECRET` | generated secret | generated secret | provider webhook + manual simulation auth; blank disables both |
| `ATTENDANCE_SMS_SEND_AFTER` | `00:00` for an immediate staging smoke, else `10:30` | `10:30` | register-close cutoff |
| `ATTENDANCE_SMS_WINDOW_START` / `WINDOW_END` | `00:00` / `23:59` for the smoke | `08:00` / `17:00` | nuisance window |
| `ATTENDANCE_SMS_SCHOOL_DAYS` | include today for the smoke | Mon-Fri | school days only |
| `ATTENDANCE_SMS_SCAN_INITIAL_DELAY_MS` / `SCAN_MS` | `60000` / `60000` for the smoke | defaults (10 min) | sweep timing |
| `ATTENDANCE_SMS_BUDGET_DEFAULT_KES` | `2000` | `2000` (D5) | per school per term |
| `ATTENDANCE_SMS_LOW_BALANCE_THRESHOLD_KES` | `20` | `20` | one ICT-admin alert per term |
| `ATTENDANCE_SMS_DLR_SIMULATION_ENABLED` | `true` (staging yml default) | **must stay false / unset** | staging-only simulator |
| `ATTENDANCE_SMS_DLR_SIMULATION_DELAY_SECONDS` | `60` | n/a | only auto-report messages older than this |
| `ATTENDANCE_SMS_DLR_SIMULATION_OUTCOME` | `DELIVERED` (or `FAILED`) | n/a | auto-sweep outcome |
| `ATTENDANCE_SMS_DLR_SIMULATION_LIMIT` | `50` | n/a | cap per auto-sweep |
| `ATTENDANCE_SMS_DLR_SIMULATION_INITIAL_DELAY_MS` / `SCAN_MS` | `30000` / `30000` | n/a | staging sweep schedule |

Production safety checks before every deployment:

1. `SPRING_PROFILES_ACTIVE=prod`.
2. `ATTENDANCE_SMS_DLR_SIMULATION_ENABLED` is unset or `false`.
3. `ATTENDANCE_SMS_DLR_SECRET` is set only if the provider can authenticate the callback.

---

## 4. Staging sequence

### 4.1 Boot and migrate

1. Deploy the current API build with `SPRING_PROFILES_ACTIVE=staging` and the staging credentials.
2. Confirm Flyway applied V12, V13 and V14 and the app is healthy:
   `GET /actuator/health` -> `UP`.
3. Confirm the staging simulator beans loaded (log or actuator): the endpoint
   `POST /webhooks/sms/dlr/simulate` exists only in staging.
4. Leave `PHONE_VERIFICATION_ENABLED=false` and `ATTENDANCE_SMS_ENABLED=false` for this boot.

### 4.2 Phone verification smoke (three networks)

1. Set `PHONE_VERIFICATION_ENABLED=true`, `PHONE_VERIFICATION_PROVIDER=hostpinnacle`, restart.
2. Log in as a staff test account and call:
   `POST /auth/phone/verification/request` with the bearer token. Expect 200 and a queued message.
3. Repeat for a learner/guardian test account on each network; record the sender ID shown on the handset.
4. Confirm each code with `POST /auth/phone/verification/confirm` and check `GET /auth/phone/verification`
   reports verified and `users.phone_verified_at` is set.
5. Negative checks: wrong code consumes an attempt (max 5), resend inside the cooldown is refused, and a sixth
   request in an hour is rate-limited.
6. Evidence: latency per network, sender ID, delivery result; no code is recorded in the evidence.

### 4.3 Attendance SMS smoke without a provider (logging gateway)

Purpose: prove policy, budget, ledger and DLR simulation with **no delivery spend**.

1. Set on staging and restart:
   `ATTENDANCE_SMS_ENABLED=true`, `ATTENDANCE_SMS_PROVIDER=logging`,
   `ATTENDANCE_SMS_SCHOOL_ALLOWLIST=<pilot-school-uuid>`,
   `ATTENDANCE_SMS_SEND_AFTER=00:00`, `ATTENDANCE_SMS_WINDOW_START=00:00`,
   `ATTENDANCE_SMS_WINDOW_END=23:59`, `ATTENDANCE_SMS_SCHOOL_DAYS=<today>`,
   `ATTENDANCE_SMS_SCAN_INITIAL_DELAY_MS=60000`, `ATTENDANCE_SMS_SCAN_MS=60000`.
2. Prepare the test guardian: verified phone, ACTIVE non-BASE subscription, opt-in, and no active push token
   (or an unread absence notification older than the read-ack window).
3. Submit an ABSENT register for that learner through the teacher flow / staging Android build.
4. Within ~2 minutes the sweep creates one dispatch row. Check:
   - `GET /school/attendance-sms/summary` (school ICT admin) shows one SMS and the KES 0.20 draw-down;
   - `GET /school/attendance-sms/dispatches` lists the row with status `SENT`, provider `logging`, a provider
     message id, and the masked phone.
5. Run the DLR simulation checks in section 5.
6. Repeat with a second absent sibling in the same family: expect **one** paid message and a second `MERGED` row at
   zero cost.
7. Negative checks: a corrected register (PRESENT) buys nothing; a LATE row buys nothing; a lapsed subscription,
   unverified phone, or opt-out each blocks the paid channel; setting the school budget to `0.00` through the
   console stops the next sweep with no SMS while in-app/push continue.

### 4.4 Attendance SMS smoke with the real provider (after the sender ID)

1. Switch `ATTENDANCE_SMS_PROVIDER=hostpinnacle` (or blank to inherit the OTP provider), keep the pilot allowlist,
   restart.
2. Use the same flow as 4.3 with the three test numbers, one network at a time.
3. For each: record the sender ID on the handset, the provider message id from the API log
   (`Attendance SMS sent ... providerMessageId=...`) or the portal, delivery latency, and the portal credit
   before/after.
4. Verify the app-side budget also moved by KES 0.20 (provider credit and app budget are separate meters).
5. If HostPinnacle sends a DLR, confirm the row's `dlrStatus`/`deliveredAt` populate; if it does not, record
   "DLR unconfigured" and continue - the channel is still usable, only without delivery proof.
6. Stop before the fallback chain: leave `ATTENDANCE_SMS_FALLBACK_PROVIDERS` empty unless the outage drill in
   section 8 is being run (a timeout after provider acceptance can duplicate a message).

### 4.5 Rollback drill in staging

1. Record a dispatch and its budget debit.
2. Set the school budget to `0.00` through the console and prove the next sweep buys nothing (no restart).
3. Re-arm via the flag: set `ATTENDANCE_SMS_ENABLED=false`, restart, prove no dispatch row is created.
4. Re-enable and prove the channel recovers.
5. Record the timings; production rollback uses the same two actions.

---

## 5. Staging-only DLR simulation (P6.0)

The simulator writes through the same normalised path as the real webhook
(`AttendanceSmsDlrService.applyReport`), so the ledger, console and school panels see identical data. It is not
delivery proof and must never be enabled in production.

### 5.1 Automatic sweep

When `SPRING_PROFILES_ACTIVE=staging` and `ATTENDANCE_SMS_DLR_SIMULATION_ENABLED=true` (the staging yml default):

- every `ATTENDANCE_SMS_DLR_SIMULATION_SCAN_MS` (default 30 s), after the initial delay, the scheduler finds up to
  `..._LIMIT` (default 50) `SENT` rows with no delivery report yet, older than `..._DELAY_SECONDS`
  (default 60 s), and applies `..._OUTCOME` (`DELIVERED` or `FAILED`). Rows with no provider message id (the
  **logging** gateway returns none) are included and addressed by our dispatch id;
- a replay with the same status does not move `dlrReceivedAt`; a later different status overwrites the display
  status (the real webhook behaves the same);
- set `ATTENDANCE_SMS_DLR_SIMULATION_ENABLED=false` and restart when a test must observe a row staying `SENT`.

### 5.2 Targeted manual report

Prerequisites: staging profile, simulator enabled, `ATTENDANCE_SMS_DLR_SECRET` set, and a dispatch row.
Use exactly one identifier:

- `dispatchId` - the ledger row's `id` (works for every row, including the logging gateway);
- `messageId` - the provider message id (use this to mimic a real provider report when the row has one).

```bash
curl -sS -X POST "$STAGING_API/webhooks/sms/dlr/simulate" \
  -H "Content-Type: application/json" \
  -H "X-SMS-DLR-Secret: $ATTENDANCE_SMS_DLR_SECRET" \
  -d '{"dispatchId":"<ledger-row-id>","status":"DELIVERED"}'
# Real-provider equivalent: -d '{"messageId":"<provider-message-id>","status":"DELIVERED"}'
```

Expected responses:

| HTTP | Body `outcome` | Meaning |
|---|---|---|
| 200 | `SIMULATED` | report persisted; ledger now shows `dlrStatus`/`deliveredAt` |
| 401 | `UNAUTHORIZED` | missing/wrong secret, nothing written |
| 400 | `BAD_REQUEST` | status is not DELIVERED/FAILED, or zero/two identifiers were supplied |
| 404 | `NOT_FOUND` | unknown dispatchId or messageId |
| 404 | `DISABLED` | the controller exists but `ATTENDANCE_SMS_DLR_SECRET` is blank |

If the simulator property is false or the profile is not `staging`, the route does not exist at all and the caller
gets the standard application 404 (no `outcome` body). Use `"status":"FAILED"` to prove a failed report never
sets `deliveredAt`. Both the real webhook and the simulator reject a wrong/missing secret before touching a row.

### 5.3 What to verify in the ledger

- `GET /admin/console/sms/schools/<schoolId>/dispatches` (platform operator) and
  `GET /school/attendance-sms/dispatches` (school ICT admin) show the row `id` (the `dispatchId` above),
  `deliveredAt`, `dlrStatus` and `dlrReceivedAt` after simulation.
- A second identical simulation is idempotent: `deliveredAt`/`dlrReceivedAt` must not move.
- The school summary's `smsSent`/`usedKes` do **not** change on a DLR - delivery reporting never re-bills.

---

## 6. Production rollout sequence (after the sender ID)

Do one step per change window; each step ends with the listed check and a go/no-go.

### Step 0 - pre-deploy (flags still false)

- [ ] Generate the production `ATTENDANCE_SMS_DLR_SECRET` and store it in the secrets manager.
- [ ] Set `SPRING_PROFILES_ACTIVE=prod`, all `HOSTPINNACLE_*` values including the sender ID, and the DLR secret
      (only if the provider can send the header).
- [ ] Confirm `ATTENDANCE_SMS_DLR_SIMULATION_ENABLED` is unset/false; the simulator path must not resolve.
- [ ] Deploy. Check `/actuator/health`; OTP/attendance flags remain false, so no SMS can be sent.

### Step 1 - phone verification canary

- [ ] `PHONE_VERIFICATION_ENABLED=true` (leave `ATTENDANCE_SMS_ENABLED=false`), rolling restart.
- [ ] Send and confirm one OTP per network to the test numbers; check `GET /auth/phone/verification` and the
      `users.phone_verified_at` column for one account.
- [ ] Watch provider errors and OTP verify failures for 30 minutes. Rollback: set the flag false and restart.
- [ ] Go/no-go: all three networks deliver, no credential/sender errors.

### Step 2 - attendance SMS canary

- [ ] Set the canary school's term allocation through the console (default KES 2,000; a smaller pilot cap such as
      KES 200 is fine).
- [ ] Set `ATTENDANCE_SMS_ENABLED=true`, `ATTENDANCE_SMS_SCHOOL_ALLOWLIST=<canary-school-uuid>`, keep the
      production send-after/window/school-days defaults, restart.
- [ ] Have the test guardian opt in (Android toggle) and ensure the phone is verified and the subscription is
      ACTIVE non-BASE.
- [ ] Submit an ABSENT register; wait for the sweep; verify one real SMS on the handset, one `SENT` row, the
      school's used KES increasing by 0.20, and the message text/length (one GSM-7 segment).
- [ ] Verify the channels are independent: a guardian with a read push gets no SMS; an in-app row still exists for
      an expired learner.
- [ ] Leave the canary running for 48 hours.

### Step 3 - expand and monitor

- [ ] After 48 hours with zero duplicate sends and failure rate within target: add schools to the allowlist in
      batches (one school, then 5, then 25).
- [ ] Every expansion: confirm the budget default was created and no low-balance alert fired unexpectedly.
- [ ] Only after two clean weeks: clear the allowlist (empty = every school). This is the widest change; announce it.
- [ ] Keep `ATTENDANCE_SMS_FALLBACK_PROVIDERS` empty until an outage drill justifies the duplicate-on-timeout
      trade-off.

### Step 4 - close

- [ ] Record the evidence table (section 10) and cost for the first month.
- [ ] Run the doc sweep (plan, phone_verification.md, AGENTS.md, F9 audit and ledger) and remove "blocked on sender
      ID" wording.
- [ ] Keep the staging simulator enabled only in staging; confirm the production check from section 3 in the release
      checklist.

---

## 7. Monitoring and alerts

### Surfaces

- Platform: `/admin/console/sms/schools`, `/schools/{id}`, `/schools/{id}/budget`, `/schools/{id}/dispatches`.
- School ICT admin: `/school/attendance-sms/summary` and `/school/attendance-sms/dispatches` (own school only).
- Provider portal: sender ID, credit balance, delivery reports.

### Metrics (Micrometer)

| Metric | Meaning | Threshold to investigate |
|---|---|---|
| `brainbox.sms.sent` | provider accepted a message | drops to 0 unexpectedly |
| `brainbox.sms.failures` | provider failed/rejected | >5% of sends over 15 min |
| `brainbox.sms.skipped` (tag `reason`) | policy/cost/capability skips | `budget_exhausted` appears |
| `brainbox.sms.circuit` (tag `state`) | fallback circuit state | `open` for a primary provider |
| `brainbox.sms.dlr` (tag `outcome`) | real DLR processing | `unknown_message` or `bad_payload` rising |
| `brainbox.sms.dlr_simulated` | staging simulator only | must never appear in production metrics |

### Log checks (no phone numbers)

- `Attendance SMS skipped ... reason=...` - why no paid message.
- `SMS DLR dispatch=... status=...` and `SMS DLR staging sweep ...` - delivery reports.
- `Attendance SMS budget exhausted school=...` - a school has no allocation left.
- Provider failures log the reason and dispatch id, never the number or credentials.

### Targets

- `<=400` SMS per 1,000 absence events (pilot week).
- zero duplicate sends per family/phone/day.
- provider acceptance >95%; DLR delivered >90% once DLR is configured.
- no school above its term allocation.

---

## 8. Incidents, rollback and key rotation

### Emergency brakes

| Situation | Action | Takes effect | What still works |
|---|---|---|---|
| Runaway spend / bad batch for one school | Console: set that school's term budget to `0.00` | next sweep, no restart | In-app + push unaffected |
| Stop all paid attendance SMS | `ATTENDANCE_SMS_ENABLED=false`, rolling restart | after restart | In-app + push unaffected |
| Bad/missing DLR reports | Set `ATTENDANCE_SMS_DLR_SECRET` blank, rolling restart (endpoint 404) | after restart | Sending unaffected |
| Primary provider outage | Set `ATTENDANCE_SMS_FALLBACK_PROVIDERS` (e.g. `africastalking`) and confirm sender ID, rolling restart. Accept the duplicate-on-timeout caveat | after restart | OTP unaffected unless its own provider is down |
| Provider credit exhausted | Top up the portal; app-side rows go `FAILED` but the app budget is still debited (conservative), so monitor both meters | immediately after top-up | In-app + push unaffected |
| Wrong sender ID / 401 from provider | Fix secrets, rolling restart; rows already `FAILED` are not retried | after restart | In-app + push unaffected |

**Flag changes need a restart.** If a rolling restart is not possible, the budget brake is the only no-deploy lever.

### Outage drill (staging)

1. Set `ATTENDANCE_SMS_PROVIDER=logging` or point the primary at an unreachable base URL; prove failure rows and
   metrics, no retry, no register impact.
2. Configure the fallback provider and prove one send routes to it (with the duplicate caveat accepted).
3. Restore the primary; clear the fallback list.

### Key rotation

| Secret | Rotation |
|---|---|
| `HOSTPINNACLE_API_KEY` / password | rotate in the portal, update the secrets manager, rolling restart, confirm one OTP + one attendance send |
| `ATTENDANCE_SMS_DLR_SECRET` | generate a new secret, set env, rolling restart, update the provider callback header at the same time; a mismatch only causes 401s, never data changes |
| `AFRICASTALKING_*` / `BEEM_*` | same pattern; only needed when that fallback is active |

No database migration or client release is involved in any rollback or rotation.

---

## 9. Cost and credit

- Price fact: **KES 0.20 per SMS unit** (HostPinnacle packs: 250 = KES 50, 1,000 = KES 200, 2,500 = KES 500,
  5,000 = KES 1,000).
- Two independent meters: the provider credit balance (portal) and the app's per-school term allocation (D5,
  default KES 2,000). Both must be positive; the app stops at zero **before** the provider is called, while a
  provider rejection still leaves the app budget debited (no automatic refund).
- At 500 learners, ~5,000 absence events/year, capability gating and the digest, expected spend is roughly
  KES 300-400 per school per year - within one term allocation.
- Check the portal balance daily during the canary, weekly after. Top up before it crosses 20% of the pilot
  budget; record the top-up in the evidence table.

---

## 10. Evidence checklist

Fill one row per smoke delivery (OTP and attendance separately); keep it with the change record.

| Date | Env | Network | Test number (masked) | Kind | Provider | Message id | Latency | Provider result | DLR | App budget after | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| | staging | Safaricom | +2547****123 | OTP | hostpinnacle | | | | n/a | n/a | |
| | staging | Airtel | +2547****456 | OTP | hostpinnacle | | | | n/a | n/a | |
| | staging | Telkom | +2547****789 | OTP | hostpinnacle | | | | n/a | n/a | |
| | staging | Safaricom | +2547****123 | absence | logging | | | | simulated | KES 0.20 | |
| | staging | Safaricom | +2547****123 | absence | hostpinnacle | | | | real/unconfigured | KES 0.20 | |
| | prod | Safaricom | +2547****123 | absence canary | hostpinnacle | | | | | KES 0.20 | |

Sign-off: platform owner (date), on-call engineer (date), cost owner (date). Attach the console ledger extracts and
the metric screenshots; never attach secrets or full phone numbers.

---

## 11. Open items this runbook deliberately does not close

1. **HostPinnacle DLR body and callback capability are unobserved.** If the provider cannot send
   `X-SMS-DLR-Secret`, leave the webhook disabled; the parser currently accepts JSON or form bodies and normalises
   keys at depth <=3 and must be pinned to the first real payload.
2. **Fallback duplicate-on-timeout** is a real trade-off; keep the fallback list empty until an outage drill is
   accepted by the owner.
3. **No-smartphone families (ROSTER_ONLY learners)** still have no SMS channel under F9; that needs a school-level
   guardian-phone product decision, not a config change.
4. **Risk threshold (D3)** stays off until P0.3 measurements exist; do not enable it for the canary without a
   decision record.
5. **Push delivery ledger (D6)** remains deferred; SMS DLR is the only provider-delivery proof.

---

*Written for Phase 6 (P6.0 staging DLR simulation, P6.3 runbook). Code references:
`AttendanceSmsDlrSimulator`, `AttendanceSmsDlrSimulationController`,
`AttendanceSmsDlrSimulationScheduler`, `AttendanceSmsDlrService.applyReport`,
`AttendanceSmsScheduler`, `AttendanceSmsProperties`. Configuration is in `application.yml` and
`application-staging.yml`; the contract is in [sms_and_attendance_notifications_plan.md](sms_and_attendance_notifications_plan.md)
sections 6, 7 and 15.*
