# Account model — one account per person (F9/N6)

**This is a product rule, not an implementation preference.** The owner unified the learner and guardian
accounts: a learner's account **is** the guardian account. Do not add a second account type, a parent-only
signup, or a client-side account switch.

## The rule

- One `users` row per person. A `STUDENT` account is its own guardian context: the guardian surfaces
  (`/parent/**`) resolve the learner's own row.
- `POST /auth/signup` accepts **STUDENT** and **TEACHER** only. `PARENT` and `ADMIN` are refused; school
  administrators are created by the platform console.
- A legacy `PARENT` row is an **alias** during the transition. It can still be created by the console and linked to
  a learner, and it keeps access to the guardian surfaces, but nothing may create a new one by signup.
- Anything addressed to "parents" reaches the **learner's own inbox** (messages already use
  `intendedForParent`; announcements, school announcements and attendance now target the learner row too).
- Learner credentials are a **4-digit PIN** (`credential_kind = PIN`); staff keep an 8+ character password.
  Login never validates the shape (legacy learner passwords keep working); change/reset enforce the stored kind.
- Phone numbers are stored canonically (`0712345678`); signup accepts +254/254/07/01 forms.

## Enforcement points (keep these in sync)

| Concern | Server |
| --- | --- |
| Guardian context = self | `parent/ParentPortalService.kt` (`linkedChildren`, `requireLinkedChild`, `requireGuardianContext`) |
| No PARENT signup | `auth/AuthService.kt` (`parseSignupRole`) |
| Guardian audience | `announcement/AnnouncementService.kt` (`recipients`, `visibleTo`), `announcement/SchoolAnnouncementService.kt` (`guardianAudienceOf`, `legacyReach`) |
| Credential kind | `auth/CredentialRules.kt`, `users.credential_kind` (V11) |
| Phone canonical form | `auth/PhoneNumbers.kt` |
| Approval decisions audited + sessions revoked | `auth/TeacherAuthService.kt` (`decideUser`, `applyStatus`) |

## Clients

- Android: the learner's notch has a **Guardian** group (PIN gate, analytics, conference, notification
  preferences); do not re-introduce a separate parent menu or a parent signup route.
- Web: there is no learner-facing surface; the teacher login accepts a 4-digit PIN (no length floor at login).

## Not done yet (deliberately)

- **Google phone verification** needs SMS/Firebase infrastructure the platform does not have yet; the approval
  pipeline remains the gate.
- **Alias merge of existing parent rows** (non-destructive re-pointing across 20+ FKs) is a separate migration
  slice; until then legacy PARENT rows keep working as aliases.
