# Email verification, the signup directory and the account-state gate

Status: implemented (gaps.md items 1–4). This document is the contract the web and Android
clients and the API all implement.

## 1. Staff signup contract — `POST /auth/signup/teacher`

```json
{
  "name": "Jane Wanjiku",
  "phoneNumber": "0712345678",
  "password": "at-least-8-chars",
  "email": "jane@example.com",
  "schoolId": "<uuid from /auth/signup/schools>",
  "subRole": "TEACHER | CTEACHER | GRADE_COORDINATOR | ICT_ADMIN",
  "grades": ["Grade 4"],
  "className": "Grade 4 East",
  "subjects": ["Mathematics"],
  "tscNumber": "TSC-1",
  "stayLoggedIn": true
}
```

Rules enforced **server-side** (the client may not relax them):

- `email` is required, normalised to lower-case and must be unique (case-insensitive).
- `schoolId` is required and must be an active school from the public directory. A teacher
  can never create a school by typing a name.
- `subRole` decides the fields:
  - `ICT_ADMIN` (the "ICT Admin / HOI" card): no grades and no class. Only the **first** ICT
    admin of a school may self-sign-up; a later one must be approved by the existing admin or
    the platform team.
  - `CTEACHER`: exactly one grade **and** one class, both from the school's catalogue.
  - `GRADE_COORDINATOR`: a **class teacher with grade-wide duties** — exactly one grade **and**
    one class from the catalogue, like a class teacher, and the signup creates the class row that
    their Class Teacher Code enrols learners into. A grade has **exactly one** coordinator: a
    second signup for the same school + grade is a **409** naming the grade, and rejecting,
    freezing or deactivating the holder frees the slot. The check runs under a school-row lock,
    so two concurrent requests cannot both win.
  - `TEACHER`: one or more grades from the catalogue, no class.
- A school with no configured classes cannot accept class-teacher signups: its ICT admin
  configures the catalogue first (`PUT /admin/schools/{id}/config`, `classes[]`).
- If the school's `registrationOpen` is false the request is **403** with the school name in
  the message; the client shows that message.
- `studentCount` is not part of the contract: it is neither asked for nor returned.
- The response has `success`, `user`, `ctc`, `ctcShareText`,
  `emailVerificationRequired: true` and `maskedEmail` — and **`sessionToken: null`**.
  No session exists until the emailed code is confirmed.
- The CTC is issued at signup so it can be shared later, but `POST /auth/validate-ctc`
  refuses it while the teacher is not `VERIFIED`.

## 2. Signup directory — public reads

- `GET /auth/signup/schools` → `[{id, name, registrationOpen}]`
- `GET /auth/signup/schools/{schoolId}` → `{id, name, registrationOpen, grades[], classes[{name, gradeLevel}]}`

Only names and ids are published. No learner counts, staff counts or learner data.

## 3. Email verification

- `POST /auth/email/send-code {identifier}` — neutral for known/unknown addresses, throttled.
- `POST /auth/email/verify {identifier, code}` → `AuthResponse` **with** a session token.
- 6-digit code, hashed at rest, single use, 10-minute TTL, 5 attempts, 60 s resend cool-down,
  5 requests/hour/account.
- `POST /auth/login` with a correct password but an unconfirmed address answers **403**
  `EMAIL_NOT_VERIFIED`, with `details.email` / `details.maskedEmail`, so the client can
  move to the code step.
- Transport: one `TransactionalEmailSender` seam. Production selects `MAIL_PROVIDER=mailjet`
  (SMTP through `in-v3.mailjet.com`); the development default is the logging sink. The code is
  never returned by any endpoint.

### Configuration

| Environment | Meaning |
|---|---|
| `MAIL_PROVIDER` | `mailjet` (production) or `logging` (development sink) |
| `MAIL_FROM` | verified Mailjet sender address (sending fails loudly if unset) |
| `MAIL_FROM_NAME` | display name on the message |
| `MJ_APIKEY_PUBLIC` / `MJ_APIKEY_PRIVATE` | Mailjet SMTP username/password |
| `EMAIL_VERIFICATION_ENABLED` | default `true`; `false` disables the gate only for new signups |
| `EMAIL_VERIFICATION_CODE_TTL` etc. | code lifecycle overrides |
| `MAIL_LOG_BODY` | development only: print the message body (contains the code) |

Password-reset codes use the same transport: `POST /auth/forgot-password` emails the account
address when a real transport is configured.

## 4. "Which email did I sign up with?"

- `POST /auth/recover-email {identifier}` (phone number or email) →
  `{found, maskedEmail}`. Only the masked address is disclosed (e.g. `j***@example.com`).
- `POST /auth/verify-otp` also returns `maskedEmail` once the reset code is verified.
- Both are per-identifier throttled and sit behind the platform IP rate limit. The masking
  trade-off (it does reveal that an account exists) is deliberate: the product needs a
  recovery path, and the returned value cannot be used to sign in.

## 5. Account-state gate (server-enforced)

`AccountStateFilter` runs after bearer authentication and refuses, with a machine-readable
403, every data route for:

- `ACCOUNT_INACTIVE` — deactivated / rejected / frozen accounts;
- `EMAIL_NOT_VERIFIED` — accounts that still owe the email code;
- `ACCOUNT_PENDING` — **staff** accounts awaiting ICT-admin approval (`user.role=TEACHER`).

`/auth/**`, public reads and provider webhooks stay reachable. The client's waiting screen is a
courtesy; a pending teacher cannot read school data by calling the API directly.

## 6. Verification

- Server: targeted auth suites plus the full suite (see the change log in the commit).
- The Mailjet SMTP channel was verified live against the owner account before this shipped.
