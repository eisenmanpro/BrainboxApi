# Phone verification — provider research and decision

**Status: DECIDED (providers), NOT IMPLEMENTED (code).** This record supersedes the "Google phone verification"
residual wording in the F9 audit and in [account_unification.md](account_unification.md). Owner decision (this pass):
record the research, then proceed with **Africa's Talking** as the primary SMS provider and **Beem** as the
pilot/fallback.

Recorded: 2026-11-12. Prices were checked live against provider pages during the research pass and are indicative only.

## Decision

1. **Architecture: server-side OTP over an SMS gateway.** We generate, hash, expire and attempt-limit the code; the
   provider only delivers the SMS. This reuses the password-reset OTP machinery and gives one flow for Android and web,
   with no client SDK and no second identity authority.
2. **Primary provider: Africa's Talking** — KES 0.80/SMS standard route, free sandbox for development, M-Pesa top-up,
   local delivery, developer-friendly API. Sandbox credentials are free and work only to whitelisted test numbers.
3. **Pilot/fallback provider: Beem** — apply to the Anza startup programme first (5,000 free SMS or 1,000 WhatsApp
   broadcasts, plus two months of shared shortcode/USSD), then take a paid quote. It is the second local route if
   Africa's Talking has an outage or its sender-ID registration stalls.
4. **The provider sits behind a seam**, exactly like the password-reset notifier and the FCM push sender: a
   PhoneVerificationNotifier interface with logging, Africa's Talking and Beem implementations selected by config, so
   switching providers is a small class change, not a rewrite.
5. **Default-off feature flag.** Nothing is sent or enforced until an operator activates a provider. The approval
   pipeline remains the real gate (F9-D5); phone verification is an additional signal. Whether a verified phone is
   *required* before approval is a product decision left open — see "Product decisions still open".
6. **Firebase Phone Auth is rejected as primary.** It now requires Blaze billing, charges **$0.15 per SMS to Kenya**
   (first 10/day free), needs reCAPTCHA/domain configuration on web, adds a client dependency, and would make Firebase a
   second identity authority beside our own auth. Its SMS-free "Phone Number Verification" product is Android-only and
   carrier-dependent with no published Kenyan carrier support.

## Current state (honest)

No verifier code exists today: no endpoint, no challenge table, no users.phone_verified_at column, no notifier, no config
flag. The phone is stored canonically in 07xxxxxxxx form on both signup paths, and phone editing is not offered by the
profile update contract, so there is no re-verification-on-change path yet. No user-facing screen claims a phone is
verified, so there is no false claim to unwind (D15 is clean).

## Architecture options considered

| Option | How it works | Trade-off |
|---|---|---|
| SDK phone auth (Firebase) | Client SDK requests the SMS; provider issues/checks the code; server verifies an ID token | Best Android auto-retrieval UX, but billing + console + web reCAPTCHA + second identity authority; most moving parts and highest cost in Kenya |
| Verify API (Twilio Verify, Vonage Verify, Telnyx Verify, Infobip Verify) | Our server calls start/check; provider owns the code lifecycle | Less server code, but a per-success fee, the vendor owns the OTP, and we still record our own verified state; useful for multi-channel fallback |
| Raw SMS gateway + our own OTP (Africa's Talking, Beem, Infobip SMS, AWS SNS, Plivo) | We own the code (already proven for password reset); provider delivers the SMS | Cheapest, one flow for both clients, no client dependency, full control; we own rate limiting and abuse handling |

## Provider comparison (research summary)

| Provider | Model | Free tier / trial | Kenya cost | Verdict |
|---|---|---|---|---|
| **Africa's Talking** | Raw SMS gateway (USSD/voice too) | Free sandbox (whitelisted numbers); no free production SMS | ~KES 0.80/SMS | **Primary** — local, cheap, sandbox, M-Pesa |
| **Beem** | Raw SMS gateway (East Africa) | Free signup; **Anza: 5,000 free SMS** (or 1,000 WhatsApp) + 2 months shortcode/USSD | Quote; competitive regional | **Pilot/fallback** — apply before paying |
| **Twilio Verify** | Verify API + SMS | $15 trial credit (verified recipients only; trial prefix) | $0.05/success + $0.3134/SMS ≈ **$0.3634** | Excellent docs and WhatsApp/voice fallback; ~45–60x AT for the same Kenyan outcome — fallback-only |
| **Firebase Phone Auth** | SDK phone auth | First 10 SMS/day free; **Blaze billing required** | **$0.15/SMS** | Rejected as primary (billing, web reCAPTCHA, second identity authority) |
| Firebase PNV (SMS-free) | Carrier lookup, no SMS | — | — | Android-only, no published Kenyan carrier coverage — watch item, not a plan |
| **Infobip** | Verify + SMS + omnichannel | 60-day trial: per-channel credits, 2 verified recipients, shared test sender | SMS ~$0.2147 (Oct 2025) + verification fee | Enterprise/SLA or WhatsApp option; sales-led |
| Vonage Verify | Verify API + SMS | "Try it free" trial (credit amount not published) | $0.06084/success + messaging | Works, but no Kenya price transparency; dominated by Twilio on docs and AT on local cost |
| Telnyx | Verify + SMS | Trial available | $0.03/success + SMS; Kenya rate/coverage unverified | Cheap on paper; quote and test Kenyan delivery first |
| AWS SNS | Raw SMS | No SMS free tier (free tier is push/email) | Console quote | Dominated by AT locally; IAM overhead, no local sender ID |
| Plivo | Raw SMS | Trial credits, no card | Long code from $0.1398 on Safaricom but $0.71 on Airtel / $0.60 Telkom | Network-dependent blended cost; not a launch choice |
| Local resellers (SMSLeopard, Textlocal, HostPinnacle) | Raw bulk SMS | Small test credits | ~KES 0.4–1.2, volume-tiered | Volume play / third fallback; API and uptime risk |
| Safaricom direct | Bulk SMS | None | KES 1.06 at 25k–125k/month, down to KES 0.11 at 10M+ | Not viable below ~25k messages/month |

Raw SMS is short: an OTP fits in one 160-character segment. Alphanumeric sender IDs can alter delivery and cost.

## Costs at BrainBox scale

One verification = one OTP SMS (one segment), ignoring retries and failed attempts (Twilio bills the SMS even when the
verification fails).

| Provider | 1,000/month | 10,000/month |
|---|---|---|
| Africa's Talking | KES 800 (~$6) | KES 8,000 (~$62) |
| Beem | quote; free up to 5,000 via Anza | quote |
| Firebase Phone Auth | ~$105–150 (first 10/day free) | ~$1,350–1,500 |
| Twilio Verify | ~$363 | ~$3,634 |
| Infobip | ~$215 + verification fee | ~$2,150 + |
| Telnyx Verify | ~$30 + SMS (Kenya rate unverified) | ~$300 + SMS |
| Plivo | ~$140–710 depending on network mix | network-mix quote |

At a realistic school-signup volume (hundreds/month), Twilio's absolute cost is not catastrophic, but it is the same
outcome for ~45–60x the price, and local routing is better within Kenya.

## Implementation plan (when activated)

Server (Spring Boot, BrainboxApi-master):

- **Config** under app.auth, default off, following the existing password-reset pattern:
  - phone-verification.enabled (default false), phone-verification.provider (logging | africastalking | beem),
    phone-verification.log-otp (default false), phone-verification.require-for-approval (default false),
    phone-verification.code-ttl (10 minutes), phone-verification.max-attempts (5).
  - Provider credentials in env: AFRICASTALKING_USERNAME, AFRICASTALKING_API_KEY, AFRICASTALKING_SENDER_ID;
    BEEM_API_KEY, BEEM_SECRET, BEEM_SENDER_ID. Keys never logged.
- **Notifier seam**: auth/PhoneVerificationNotifier.kt with LoggingPhoneVerificationNotifier (dev),
  AfricasTalkingSmsNotifier (POST to the Africa's Talking messaging endpoint; sandbox host for dev), BeemSmsNotifier
  (POST to Beem's send endpoint), selected by config.
- **OTP core**: extract the hashed-code/attempt/TTL logic shared with password reset (or a sibling service) so the two
  flows cannot drift. One active challenge per user; resend cooldown; per-phone and per-IP rate limits.
- **Migration V12**: users.phone_verified_at (nullable timestamp) and phone_verification_challenges
  (id, user_id, phone_number, code_hash, attempts, sent_at, expires_at, consumed_at) with indexes; a phone change clears
  verification.
- **Endpoints** (authenticated learner/teacher): POST /auth/phone/verification/request (409 already verified / 429
  recently sent) and POST /auth/phone/verification/confirm { code } → 204 and sets phone_verified_at; GET /auth/me
  exposes phoneVerified. When the flag is off, endpoints answer "not enabled" and clients hide the card.
- **Enforcement** (product-gated): when require-for-approval is on, the approval decision refuses a PENDING account
  with an unverified phone, with a server-authored reason; default off so activation never blocks existing accounts.
- **Tests**: request/confirm happy path, expiry, attempt cap, resend throttle, provider disabled, already verified,
  phone-change clears verification, MockMvc authorization, and a notifier unit test that asserts the SMS body contains
  the code and nothing else sensitive.

Clients:

- **Android**: a "Verify your phone" card on the signup-success / pending-approval surface, with request + code confirm
  + resend timer, honest rate-limit and provider-failure states; approval copy still says the admin decides.
- **Web**: the same card on the pending-approval / teacher surface (the web has no learner-facing surface today), hitting
  the same endpoints.
- Both clients keep the phone read-only until a verified-change flow exists.

## Activation checklist (external)

1. Choose the live provider for the term: Africa's Talking primary; Beem if Anza is approved first or AT registration stalls.
2. Africa's Talking: create account, KYC, register the sender ID (Kenya: typically 2–5 business days, letterhead
   required), fund via M-Pesa or card, put credentials in env, run a staging smoke test to Safaricom/Airtel/Telkom numbers.
3. Beem: apply to Anza (5,000 free SMS), take a paid quote, register sender ID, fund, credentials in env.
4. Flip PHONE_VERIFICATION_ENABLED and the provider; keep require-for-approval off until the owner decides.
5. Add consent copy (DPA 2019 / ODPC) explaining the OTP is transactional and the number is used for account recovery.
6. Monitor delivery receipts and failure rate per network before enforcing.

## Product decisions still open

- Who must verify: teachers before approval, learners/guardians at signup, or both?
- What an unverified account may do: blocked approval badge, blocked login, or just an admin-visible signal?
- Backfill: grandfather existing accounts (no data to verify against) or require verification at first login?
- Web parity: the web has no learner surface; is a teacher-only web card enough?
- **OD-1 (siblings / shared guardian phone):** verifying a number proves control of the line, not identity, and siblings
  still cannot share one guardian number until "learner's own phone + guardian contact" is decided. Do not treat phone
  verification as a fix for that.

## Caveats

- Prices are indicative and change; Kenya SMS is often quoted per network and alphanumeric sender IDs can alter delivery.
- Local aggregators deliver ~98–99% in-country; international routes can be slower/lower-delivery. Test real numbers.
- SIM swap and shared guardian phones mean phone verification proves line control only; the approval pipeline must stay
  the real gate.
- OTP traffic must stay transactional and consented; marketing SMS in Kenya is regulated by CAK/ODPC with sending-hour
  rules.
- The phone is read-only in the profile update contract today; if phone editing is added, verification must be cleared
  and re-run on change.

## Sources

- Identity Platform pricing: https://cloud.google.com/identity-platform/pricing
- Firebase pricing: https://firebase.google.com/pricing
- Twilio Verify pricing: https://www.twilio.com/en-us/verify/pricing
- Twilio Kenya SMS pricing: https://www.twilio.com/en-us/sms/pricing/ke
- Twilio trial credits: https://www.twilio.com/en-us/signup-credits
- Africa's Talking bulk SMS: https://africastalking.com/sms/bulksms
- Africa's Talking developer guide (sandbox, M-Pesa top-up): https://helloduty.com/blogs/sending-bulk-sms-via-africas-talking-api
- Kenya SMS cost/ROI guide: https://mocky.co.ke/blog/bulk-sms-marketing-in-kenya-costs-compliance-and-roi-guide-for-smes-in-2026
- Kenya provider comparison: https://www.sent.dm/en/resources/sms-pricing/kenya-sms-pricing
- Beem Anza launch: https://beem.africa/blog/beem-launches-anza-to-help-10000-african-startups-start-build-and-scale/
- Infobip free trial: https://www.infobip.com/docs/get-started/build-and-go-live/free-trial
- Telnyx pricing file: https://telnyx.com/ai/pricing.json
- Vonage Verify pricing: https://www.vonage.com/communications-apis/verify/pricing/
- AWS SNS SMS pricing: https://aws.amazon.com/sns/sms-pricing/
- Plivo Kenya pricing: https://www.plivo.com/sms/pricing/ke/
- Firebase phone number verification product: https://firebase.google.com/docs/phone-number-verification
