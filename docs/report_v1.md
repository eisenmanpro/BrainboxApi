# Report template v1 — server contract and learner rules

Status: implemented on the `design/report-v1` branch (2026-11-15). The design authority is
`REPORT_V1_INTEGRATION_TASKS.md` in the workspace root; this file records what the API now does so the
document formats, data enrichments and learner rules survive independently of that plan.

## Document formats

The server renders all nine report types into PDF through `report/render` (PDFBox, A4):

| Type | Format |
|---|---|
| `CBC_CLASS` / `DETAILED_CBC_CLASS` | Class CBC overview (KPIs, strand bars, subject/teacher impact); detailed adds a weak-strand recommendations page only when a recommendation exists |
| `CBC_STUDENT` / `DETAILED_CBC_STUDENT` | Learner CBC card; detailed adds a second page with engagement/attendance/level-mix analytics and no strand-table repeat |
| `TRADITIONAL_STUDENT` | Ranked exam card: subject levels, class position, remarks, session-band legend |
| `TEACHER_PERFORMANCE` | One teacher's subject delivery, strand impact, coverage, next step |
| `TRADITIONAL_COMBINED` | Whole-grade ranked list: `SCORE · % · LVL` per subject, `TOTAL = Σ %`, learner `MEAN`; portrait at ≤5 subjects, landscape 6–9, compact landscape 10–13; incomplete learners listed separately and excluded from ranks/totals/means |
| `TRADITIONAL_PER_CLASS_TABLES` | **One PDF per class** (ranked table, class totals, that class's incomplete table); the `classTag` filter selects one class, blank/null = ALL and the job returns a zip of one PDF per class |
| `TRADITIONAL_GRADE_ANALYSIS` | Landscape table (MEAN+POS pairs in exam order, weighted grade mean, callouts) plus subject-teacher contribution chart pages with an Unassigned bar and current-assignment footnote |

Shared rules: percentage = raw ÷ subject max × 100, combined subjects sum their components first; overall
level = band of the learner's mean percentage; blank templates render the v1 shell with a TEMPLATE watermark
and no invented marks; the Exam Office stamp (or the school's uploaded stamp) signs cards/analysis and the
last list page; dense list pages carry a 4 % watermark.

## Data enrichments

- **D1** `CbcStrandRatingPayload.strandName` — the strand catalogue name (never `CBC1..7`).
- **D2** `CbcClassReportPayload.studentCount` — real roster count, including provisioned learners.
- **D3** `TraditionalGradeAnalysisDto.overallMeanPercentage`/`generatedAt` and the weighted learner grade mean.
- **D4** `TeacherPerformanceSpec` — teacher-only subject/strand data, batch-loaded.
- **D7** `PaperExamResultPayload.examId/examTitle/term/year/examDate` — the CBC card's paper block shows only the
  **latest PUBLISHED paper exam in which the learner has marks**; `TraditionalExamService.latestPublishedPaperExamFor`
  is deterministic (newest year → term ordinal → publication time) and returns null when none.
- **D12/D18** — learner exam history and per-session grading snapshots (below).
- **D16** — `SchoolConfigPayload.logoUrl/stampUrl` flow into `ReportBranding`; the BrainBox mark and the standard
  Exam Office stamp remain the fallbacks. Both can be uploaded through the media service (below).
- **Roster-only learners** (imported, no `class_memberships` row) are visible to class CBC reports/analytics and
  are accepted by mark writes that carry their `classId`.

## Learner/parent rules (decisions 2/8/12)

1. **CBC card paper-exam block = latest published paper exam only.** Earlier paper exams are deliberately not
   accumulated on the card (the CBC part already reflects the most recent exam).
2. **Previous finalised exams are reachable through `TRADITIONAL_STUDENT`.** `GET traditional/exams` as
   STUDENT/PARENT returns every **PUBLISHED** exam the learner has a mark for, across terms, years and previous
   grades, school-scoped. Another learner's exams and FINALIZED-but-unpublished exams never appear; the staff
   list is unchanged.
3. **Learners and parents are unmetered.** `GET student/reports/quota` answers `metered=false`; only TEACHER
   accounts consume the lifetime student-report allowance. Learner screens must not show quota UI.
4. **Reports regenerate from the database.** Report jobs/files are pruned after the retention window
   (`app.report.retention-days`, default 90), and learners keep no stored-report index: asking again renders a
   fresh PDF from marks/exam data, so retention never blocks a re-download.
5. `POST student/reports/generate` accepts `examId` for `TRADITIONAL_STUDENT`; a learner may only request their
   own report, a parent only a linked child, a teacher only a learner of record. Report types exposed to learners
   are `CBC_STUDENT`, `DETAILED_CBC_STUDENT` and `TRADITIONAL_STUDENT`.

## Session bands (D18)

Capture-session create/edit accepts `grading.bands` (labels + thresholds, strictly descending 0–100) and
`grading.overallBands`; absent bands resolve from the school+grade last-used config, then platform defaults
(EE 80 · ME 65 · AE 50 · BE 35 · DE 20 · E 0). The resolved list is snapshotted on the exam
(`traditional_exams.grading_snapshot`, V16), echoed by `TraditionalExamDto.grading` for prefill, written back
as last-used, and used by every mark write, report level, legend and grade mean. Changing a later grade setting
never re-bands an old exam. `GET traditional/grades/{grade}/grading` returns the prefill.

## Branding images (D16/A23)

`POST admin/schools/{schoolId}/config/branding/{logo|stamp}` (multipart field `file`) stores the image through
the shared media object store and returns `{ kind, url }`; the admin saves that URL with the existing config
PUT, so a cancelled form leaves the saved branding untouched. ICT admin (own school) or platform admin only;
PNG/JPEG/GIF, 2 MB max — the same 2 MB the renderer decodes, so a stored image is always renderable (WebP is
refused rather than silently dropped). The URL is a public `/media/{uuid}.{ext}` link; the renderer resolves
a key written by this server straight from the object store instead of fetching it over HTTP, so branding
renders the same for a loopback development origin as on a public deployment. A pasted http(s) or
`data:image/` URL still works and keeps the SSRF-guarded fetch path.

## Verification

- Renderer/preview: `ReportPreviewTest` writes all nine PDFs + PNGs to `build/report-previews`;
  `ReportScaleMatrixTest` asserts the 500-learner / 5-class / S=5,9,13 matrices numerically.
- Web: `npm test` (1434) + browser smoke 117/117; Android: `:app:assembleProdDebug` +
  `:app:testProdDebugUnitTest` (400 tests) with the nine canonical labels/descriptions pinned.
- The 9×3 label/description matrix is equal across `ReportTitles` (API), `ReportDomain.REPORT_TYPES` (web) and
  `ReportType` (Android).
