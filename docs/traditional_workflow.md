# Traditional exam submission → finalisation → publication (V41)

Server contract for the owner's web workflow. The web is implemented against these routes;
Android remains to be updated (recorded in `BrainboxWeb-main/mismatched.md` EXAM-21).

## Status mapping

No new exam status was introduced. "Submitted" is `confirmedByMe` on `TraditionalExamDto` plus a
`traditional_confirmations` row; exam-level `CONFIRMED` still means all assigned teachers submitted.
`PRE_FINAL`, `FINALIZED` and `PUBLISHED` keep their existing meaning.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/traditional/exams/{examId}/submissions` | `SubmissionSummaryDto`: per class `{classTag, teacherId, teacherName, submitted, submittedAt, studentsWithMarks, archived}`. Coordinator-scoped. |
| POST | `/traditional/exams/{examId}/finalize` | Existing params plus `includedClassTags` (comma-separated), `auditWindowMinutes`, `disregardAcknowledged`. |
| POST | `/traditional/exams/{examId}/audit-window/extend?minutes=` | Extend a FINALIZED exam's deadline; never shortens an existing future deadline; bounded 1–10080 minutes. |
| POST | `/traditional/exams/{examId}/broadcast` | Body `{message}`; notifies the included classes' teachers while FINALIZED or PUBLISHED. |
| POST | `/traditional/exams/{examId}/publish` | Refuses while `auditDeadlineAt` is in the future; sets `PUBLISHED` and fans out only to included classes' learner accounts. |
| POST | `/traditional/exams/{examId}/edit-permission/check`, `.../consume` | Existing endpoints; the web uses them for learner-scoped approved corrections. |

## Finalisation guards (server-enforced)

- Included classes must exist and each must have **at least one scored learner**. Missing learners are
  allowed — finalisation does not require a complete register.
- Ignored classes are archived (`traditional_capture_locks.archived = true`), not left pending.
- Ignoring a class with **>=10 saved-but-unsubmitted learners** requires `disregardAcknowledged=true`;
  otherwise the endpoint answers 409 so the coordinator can remind that teacher first.
- `auditWindowMinutes` defaults to 30, with 10 as the flexible lower bound and one week as the hard ceiling;
  values below 10 are accepted but the web asks for an extra confirmation.

## Publication and post-publication edits

- Publication refuses before `auditDeadlineAt`; at expiry the web prompts the coordinator to publish or
  extend — publication is an explicit choice, not a silent timer job.
- Result fan-out targets the **learner's own account** (account unification: the learner is the guardian).
  No separate PARENT account is notified.
- ICT admins (and platform admins) may correct a published score. Every correction writes a
  `traditional_published_edits` audit row and notifies the learner, that learner's class teacher and all
  ICT admins. Teachers and coordinators remain blocked with 409.
- Ignored classes are excluded from fan-out and from `GET /traditional/exams/{examId}/results/me`.

## Migration

`V41__traditional_submission_audit_window.sql` adds `audit_window_minutes`, `audit_deadline_at` and
`included_class_tags` to `traditional_exams`, `archived` to `traditional_capture_locks`, and creates
`traditional_published_edits`.

## Verification

- `./gradlew compileKotlin compileTestKotlin --offline` green.
- New `TraditionalWorkflowEndpointsWebTests` (6 tests) green: submissions counter, disregard guard,
  subset finalisation with archiving and deadline, zero-score refusal, publish gate and extension.
- `TraditionalExamWebTests` (24 tests) green after its shared `publishExam` helper was updated to advance
  the audit deadline, and its stale legacy-PARENT notification assertion was removed (account
  unification routes the guardian audience to the learner's own account).
- Full suite run before the final fixes: 1025 tests, 11 failures, 3 skipped. Six failures
  (AuthFlowWebTests, TeacherAuthWebTests, IdempotencyWebTests, ConsolePlatformWebTests) reproduce
  **unchanged at HEAD** in a clean worktree. Five TraditionalExamWebTests failures came from the old
  lifecycle helper and a stale legacy-PARENT notification assertion; that class now passes 24/24 in a
  focused rerun. The full suite was not re-run end-to-end after those fixes (one run takes ~49 minutes).
- Web-side verification: 115 files / 1838 Vitest tests green; browser workflow probe shows the guarded
  finalise dialog and zero console errors.
- **True browser↔API E2E (seeded H2 server on 8080, web SPA on 8797 proxying to it):** 11/11 steps green —
  real coordinator login, server-backed submissions counter, disregard guard on 7B (12 saved-but-unsubmitted
  learners), subset finalisation with only 7A included and an audit deadline, window extension through the UI,
  publish after expiry, learner-account result read (403 before publish → 200 after), ICT-admin published edit
  allowed / class-teacher edit refused (200/409), and the learner notification for that correction. Evidence:
  `/home/deadman/.hermes/cache/scratch/e2e/report.json` and `e2e/screenshots/e2e-0*.png`.
  This run exposed and fixed a real bug: the published-edit override was bypassed at the top of `saveMarks`
  but still caught by the later lock/capture-lock checks. Those two guards now honour `publishedOverride`.


## Submission counters and roster-only learners

`studentsWithMarks` on each `GET /traditional/exams/{examId}/submissions` row counts every student whose
resolved class tag matches the row and who has at least one mark in the exam. Roster-only learners are tagged
by `provisionedClassId`, not a `class_memberships` row, so they are included. This keeps the coordinator's
finalisation guard consistent with the capture summary: a class whose teacher saved scores is never shown as
empty simply because those learners were provisioned into the class rather than enrolled through membership.
