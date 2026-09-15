# Phase 7 — Content Cold-Start: What the Client Expects and How to Make It Feel Populated

**Status:** research + design brief. Written before implementation, because Phase 7 has a
product problem (an empty platform) as much as an engineering one. Sources: the Android
tree, `docs/backend_contracts/03_…`, `ARCHITECTURE.md` §13, and the seeded catalogue
migrations.

---

## 0. The problem being solved

BrainBox starts with no content and no teacher-authored posts. The Android learning hub
renders *rails* — Featured, Trending, By subject, Continue learning, Recommended,
From your teacher, Readable materials — and until something is in them every rail is empty.
Users expect a platform that already looks alive (the dev builds fake exactly this: see
`MockLearningHubAPi.kt`). Two separate needs follow:

1. A **credible starting library** that exists on day one for every grade/subject a learner
   can open.
2. A **generation pipeline** that keeps filling gaps on demand afterwards, for far more than
   exam papers.

---

## 0.1 The BrainBox standard — the actual product, not curriculum mirroring

The premise is that current materials **miss a lot and deliver the wrong way**. That is why
BrainBox exists, so we do not reproduce curriculum text: we re-author every topic as **simple,
well-broken-down material with illustrative images/drawings and engaging questions nested in
the reading**. The target is the go-to standard for **Africa**, not a Kenyan mirror. The
curriculum is only the index (what to cover); BrainBox supplies the delivery (how it is
taught).

This is a content *specification*, and the pipeline must enforce it:

1. **Concept-first, curriculum-mapped.** A shared concept/topic layer sits *above* per-country
   curricula (Kenya CBC first, other countries later), so one concept is re-localised and
   re-mapped without re-authoring. This is also the pan-African scaling mechanism.
2. **Broken down, simple language.** Short paragraphs, one idea per step, worked examples, and
   a validated reading level — a learner reading alone at home can follow it.
3. **Visual by default.** Every concept carries a labelled diagram/drawing (inline SVG or
   image) and, where useful, a visual sequence. Visuals are a required block, not decoration.
4. **Nested engaging questions.** Check-for-understanding items interleaved through the
   material (predict, try-it, spot-the-mistake, explain-why), not only a trailing quiz.
5. **Local, African context.** Examples, names, units and money localised by country/language
   from the same concept.
6. **Enforced, not hoped for.** A style spec plus a validator chain (reading level, step
   length, visual presence, question cadence, answer correctness, localisation completeness)
   gates every item before moderation.

**Architecture consequence:** one canonical **learning unit** (steps + nested questions +
figures + provenance) rendered to whichever surface asks for it — a hub book, a readable
chunk, or an assessment — instead of authoring separately per surface. Two constraints fall
out: the generation envelope needs a *steps-with-nested-questions* shape (not body + trailing
quiz only), and a visual chunk cannot be served as `PLAINTEXT` (the client reader shows PDF,
EPUB or plain text; diagrams need the markdown/SVG path or a rendered PDF).

---

## 1. What the client actually expects of "learning materials"

There are **three distinct material surfaces**, each with its own model. They are not
interchangeable and the pipeline must feed all three.

### 1.0 The four content kinds (product model)

The corpus is two axes, not one list:

|  | Agent-generated | Teacher-uploaded |
| --- | --- | --- |
| **Chunk** (a quick topic read) | A short, self-contained topic chunk served as a readable material | The teacher's own PDF / ebook / text file |
| **Book / full topic** | A hub post — a full topic or book with ordered content blocks | A teacher-published material (same hub shape) |

Assessments — past papers, some homework, quizzes — are agent-generated regardless of size.

Consequences for the pipeline and schema:

- **Granularity is a first-class field, not just a smaller book.** A chunk is delivered
  through the **readable-materials** surface and needs *inline body text* (the endpoint is
  metadata + a file URL today — blocker 1 in §1.6). A book is a hub post with ordered
  `LearningContent` blocks.
- **Provenance is first-class on the same tables:** `generated` (agent) vs `uploaded`
  (teacher), plus author/created-by, source URLs and licence. Both are cached and delivered
  identically; the client renders teacher uploads and agent content with no new surface.
- **Teacher uploads already exist** (`teacher/content/document` → `readable_files`), so the
  agent pipeline writes into the same tables rather than inventing a parallel store.

### 1.1 Learning-hub posts — full books / topics (`LearningPost` + `LearningContent[]`)

`app/…/models/LearningHubModels.kt`

- `LearningPost` (required fields marked): `id`, `title`, `subject: Subject`, `topic`,
  `subtopic`, `gradeLevel`, `imageUrl`, `description`, `estimatedMinutes`, `difficulty` (1–5),
  `tags[]`, `createdAt`, `viewCount`, `likeCount`, optional `isFeatured`, `isTrending`,
  `cbcStrand`, `cbcSubStrand`, `authorName`, `scope` (`GLOBAL|SCHOOL|SCHOOL_GRADE_CLASS`),
  `schoolId`, `teacherId`, `customSubjectName`, `status` (`PUBLISHED|SCHEDULED|ARCHIVED`).
- `LearningContent` blocks: `type` ∈ `NOTES | VIDEO | QUIZ | FLASHCARDS | PDF | EPUB | PLAINTEXT`,
  `title`, `content`, `durationMinutes`, `orderIndex`, `thumbnailUrl?`, `metadata?`.
  - `NOTES` → **markdown** in `content`.
  - `VIDEO` → a YouTube URL in `content` (the client cannot play arbitrary video).
  - `QUIZ` / `FLASHCARDS` → **JSON in `metadata`**; the client parses
    `{"questions":[{"text":…,"options":[…],"correct":<index>}]}` and
    `{"cards":[{"front":…,"back":…}]}` (`ui/components/MiniQuiz.kt`).
- Rails the repository composes: `getFeaturedPosts`, `getTrendingPosts`,
  `getPostsBySubject`, `searchPosts`, `getContinueLearning`, `getRevisionRecommendations`,
  `getTeacherAuthoredPosts`, `getDocuments`, `getReadableFiles`.

### 1.2 Readable materials — quick topic chunks (`ReadableFile` + `DocumentItem`)

`app/…/models/LearningHubModels.kt`, `models/DocumentModels.kt`

- `ReadableFile`: `id`, `title`, `author?`, `description?`, `category`, `fileType`
  (`PDF|EPUB|PLAINTEXT`), `filePath` (URL), `totalPages`, `thumbnailUrl?`, `isFromAssets`,
  `fileSize`, `createdAt`, `lastSynced`, `isOfflineAvailable`.
- Reading state is per user: `ReadingProgress` (page, time, bookmarks, highlights,
  annotations), `ReadingSession`, `ReadingStreak`.
- This surface needs an actual **file URL** and a page count, so it cannot be served by
  raw JSON alone — the server has to render/cache a document (PDFBox is already on the
  classpath via the report renderer).

### 1.3 Past papers / homework / quizzes — generated assessments (`ExamContent`, homework)

`models/ExamTemplateModels.kt`; backend `api/exams/web/PastPaperContentDtos.kt`

- `ExamContent`: `cover` (school, student, time, questionCount, year, mcp, subject),
  `sections[]` discriminated as passage / diagram / standard, `questions[]` (type, options,
  `correctAnswer`, `explanation`, points, difficulty, `matchingPairs`, number, topic,
  subtopic) and `markingScheme` (per-question answers + marks + totals).
- **Important asymmetry:** a past paper *carries its answer key* so the client can self-grade
  offline, whereas a hub `QUIZ` is expected to **withhold** the key (`03_…` §2.2). The
  pipeline needs one stored form and two delivery projections.

### 1.4 Adjacent content the same agent will be asked for

Not "learning materials" in the hub sense, but the same generation capability: digital-exam
questions + remediation (doc 02), homework tasks and rubrics, CBC project briefs, revision
explanations for a weak topic, flashcards, study guides, and **doubt/Q&A answers** (the client
has a full Doubt surface with its own cache and outbox). Career, news, interview and contract
content is **curated/static** today (seeded in migrations) and should stay that way; learner
announcements are authored by teachers, not generated.

### 1.5 Backend state, verified

Verified against the code, not the docs:

- **All three delivery endpoints already exist**, but only two carry real bodies.
  - `GET /learning/post/{id}/content` returns `learning_content` blocks and recursively
    strips QUIZ keys (`LearningService.contentOf` / `stripKeys`).
  - `GET /past-papers/{examId}/content` is assembled per request from `exams` +
    `exam_questions`, PAST_PAPER-only, with the marking scheme embedded.
  - `GET /materials/readable/{id}` is **metadata only** (a `fileUrl` + page count, no
    body). This is the single biggest Phase 7 blocker.
- **Where bodies live:** `learning_content.content` (markdown/text) + `metadata` (QUIZ JSON,
  server-side), `readable_files.file_url` (a hosted document via `MediaService`), and
  `exams`/`exam_questions`. `learning_content` has **no file/PDF column**, so DOCUMENT-type
  materials currently misuse the text column.
- **Zero academic seed rows** in any migration: `learning_posts`, `learning_content`,
  `readable_files`, `exams`/`exam_questions`, `homework`, `news_items`, `cbc_projects`.
  The only seeded content is career catalogues (V17), the interview bank (V18),
  badges/rewards (V20), contract templates (V43) and the CBC strands (V45).
- **No Phase 7 scaffolding exists**: no content-cache/JSONB table, no agent/moderation/
  job/token-audit schema, no schema-version columns. The DeepSeek + PDF-reader dependencies
  are declared but their auto-config is excluded.
- **Bonus gap:** the *student* homework payload (`StudentHomeworkPayload`) never carries
  `questions`, so generated homework questions have no delivery path yet.

### 1.6 Delivery contract blockers (fix before generating anything)

The client inventory also found that several **existing** endpoints do not match the client
models, so content would not render even if it were seeded. These are Phase 7.0, not Phase 7.5:

1. **Readable materials field names.** The server sends `fileUrl` / `pageCount` / `sizeBytes`;
   the client reads `filePath` / `totalPages` / `fileSize` and also wants `author`,
   `description`, `thumbnailUrl`, `isFromAssets`. As written, the entity mapping cannot
   populate the Materials Hub.
2. **Hub post `subject`.** The server sends a free string; the client field is the 8-value
   `Subject` enum, so `"Mathematics"` does not survive Gson (it needs `MATHEMATICS`). Either
   canonicalise server-side or add a client `String` + parser.
3. **Hub content payload.** `LearningContentPayload` omits `postId` and types `metadata` as raw
   JSON; the client needs a non-null `postId` and `metadata` as a **JSON string**, so quiz and
   flashcard blocks currently fail to parse.
4. **Hub post `status`.** The server omits `status`; the client defaults a missing status to
   `PUBLISHED`, so **archived and scheduled posts would leak to learners**. `authorName`,
   `cbcStrand`, `cbcSubStrand` and `isTrending` are also omitted and never render.
5. **Past-paper listing.** The server returns a different `DocumentItem`; the client also needs
   `grade`, `author`, `description`, `type`, `source`, `coverUrl`, `pageCount`, `sizeBytes`.
   Without `grade` the hub grade filter is meaningless.
6. **Homework submit path.** The client posts `homework/submit` with the full `Homework` body;
   the server exposes `POST /homework/{homeworkId}/submit` with a different request object, so
   every learner submission 404s.
7. **Doubt/Q&A** (a content surface the first pass missed): the client calls
   `POST doubt/questions/{id}/bookmark` with no server mapping, and expects accept/vote to
   return the updated resource while the server returns `204`.
8. **Progress payloads** drop data: reading progress ignores bookmarks/highlights/annotations
   and completion; learning progress has no `completedContentIds`. Past-paper attempts are
   never uploaded and `GET past-papers/all` ignores `grade`.

Until these are aligned, seeding or generating content cannot make staging/prod look
populated — the dev mocks are what make dev look full.

---

## 2. Hard constraints from the existing contract

| Constraint | Detail |
| --- | --- |
| Subject enum | The client's `Subject` is fixed to 8 values (MATHEMATICS, ENGLISH, KISWAHILI, PHYSICS, CHEMISTRY, BIOLOGY, HISTORY, GEOGRAPHY); anything else must ride `customSubjectName`. |
| Grade | Canonical `GRADE <n>` / `FORM <n>` (`GradeNormalizer.kt`); CBC uses raw numbers. Seed must normalise. |
| CBC taxonomy | Server already seeds **15 strands** across Mathematics, English, Integrated Science, Kiswahili, Social Studies (`V45__cbc_strands_ratings.sql`), all `grade_level='ALL'`, with **no sub-strands or topics**. Physics/Chemistry/Biology/History/Geography have none. |
| Scope | `GLOBAL` / `SCHOOL` / `SCHOOL_GRADE_CLASS`; generated content inherits the requester's scope. |
| Answer keys | Hub quizzes strip `correctAnswer`; past papers keep the marking scheme. |
| Offline-first | Content is cached in Room and served while offline; a post must be self-contained enough to render without the network. |
| Renderability | Notes are markdown; diagrams are inline SVG/URL; video is a URL. No arbitrary HTML/JS. |

---

## 3. Cold-start strategy: three tiers

**Tier 0 — curriculum skeleton (deterministic, no LLM).** Extend `cbc_strands` with
sub-strands and topics per grade band and subject, generated from an authoritative curriculum
document rather than a model. This is the index everything else hangs off, and it is also what
makes search/strand filters look real. Rough shape: grade → subject → strand → sub-strand →
8–20 topics.

**Tier 0 status — delivered for Mathematics G4–G6 (7.5b-1).** The skeleton now lives in
`cbc_strands` (level `STRAND`/`SUBSTRAND`, `parent_id`, `curriculum_version`) plus the shared
`concepts`/`curriculum_map` layer, and is seeded from **versioned resource data**: a
`curriculum_versions` row plus `src/main/resources/curriculum/ke-cbc-v1.json`. The catalogue is
our own authored mapping aligned to the public Kenya CBC Mathematics strand and sub-strand labels;
it is not KICD text, and no KICD or KNEC document is ingested, quoted or attributed. English,
Integrated Science, Kiswahili and Social Studies, and the G7–G9 band, follow in **7.5b-2** as
further data files, so adding a subject or band is a data-only change.

**Tier 1 — a batch-generated starter library.** For every topic, produce one `NOTES` post,
one `QUIZ` and one `FLASHCARDS` set; for every subject×grade, one or two past papers and one
study-guide document. Run as a background batch (the Phase 6 scheduler exists for this),
moderated once, then cached forever. This is what removes the empty rails.

**Tier 1 status — topic `NOTES` + `QUIZ` producer delivered (7.5e).** `ContentBatchService`
resolves the leaf `topic` concepts of the Tier 0 skeleton for one Kenya CBC grade (with an optional
subject) and enqueues one deterministic job per topic × task type through the durable queue;
`POST /admin/content/batch` is the one-call path and `ContentBatchBootstrap` is the one-command
(`run-on-startup`) path. The producer defaults to `NOTES` and `QUIZ` because both project to
`learning_posts`. Flashcards are deferred: the client `ContentType` enum has no flashcards type, so
there is nothing to render them into; BOOK-like extras and the per-subject×grade past papers and
study guides stay in 7.6. Nothing the producer enqueues bypasses the router, the worker, the
safety/validator gates or the 7.5c auto-approval bar, so a clean unit publishes and anything flagged
waits in the human exception queue.

**Tier 2 — on-demand generation + cache.** The `ARCHITECTURE.md` §13 flow: router checks the
cache, generates on miss, moderates, stores, serves. Idempotent per `(type, subject, grade,
topic, scope, schemaVersion)`.

### Making it feel "already existing"

- **Stable pseudo-signals.** `viewCount`/`likeCount`/`isTrending` must be deterministic per
  post (e.g. seeded hash), not zero, and not obviously fake (spread across a plausible range).
- **Author identity.** Generated posts need an `authorName`; recommend a house byline such as
  "BrainBox Study Team" rather than a fake human, with the moderator-attribution kept in
  metadata.
- **Imagery.** `imageUrl` is required and non-null; use a deterministic set of subject/strand
  cover images (SVG generated from the subject palette) rather than random photos.
- **Narrative continuity.** "Continue learning" and revision recommendations should be driven
  by the learner's own mastery (`topic_mastery`), which the pipeline already has.

---

## 4. Content schema: from generation envelope to client delivery

`ARCHITECTURE.md` §13.4's `BOOK|PAST_PAPER|QUIZ|NOTES` envelope is a fine internal generation
contract, but the delivery layer must map it onto the three client models above. Required
changes/additions:

1. **Carry the CMS fields** the client renders: `subject`, `topic`, `subtopic`, `gradeLevel`
   (canonical), `cbcStrand`, `cbcSubStrand`, `estimatedMinutes`, `difficulty`, `tags`.
2. **Separate the answer key from the student projection.** Store `correctAnswer`/
   `explanation`/`matchingPairs` once; deliver them for past papers, strip them for hub quizzes.
3. **`metadata` blocks must match the client parsers exactly** (`questions[].correct` index;
   `cards[].front/back`) or the UI silently renders nothing.
4. **Attribution + licence fields** on every generated item (`sourceUrls[]`, `license`,
   `generator`, `agentVersion`), both for trust and for OER attribution obligations.
5. **A quality/verification block**: schema valid, curriculum-aligned, answer key verified,
   reading level, moderation outcome.
6. **Diagrams as SVG** to satisfy `ExamDiagramPayload`/`Diagram` without binary assets.

---

## 5. Grounding and licensing

The curriculum backbone is the Kenyan CBC/CBE. Five bands — Pre-Primary PP1–2, Lower
Primary G1–3, Upper Primary G4–6, Junior School G7–9 and Senior School G10–12; Senior
School started in January 2026 and splits into STEM / Social Sciences / Arts & Sports
pathways. KICD designs are organised **strand → sub-strand → specific learning outcome**, so
generated content should be authored at **sub-strand grain and tagged to the strand** — which
is exactly the `cbcStrand` / `cbcSubStrand` pair the client already carries. (Grade 5
Mathematics alone has 4 strands and about 20 sub-strands.)

Safe to ground on (permissive and commercial-friendly):

| Source | Gives | Licence |
| --- | --- | --- |
| OpenStax | science / maths / business textbooks | CC BY 4.0 |
| Siyavula (legacy CNX) | South African maths & physical sciences | CC BY 3.0 |
| PhET sim files | interactive maths/science simulations | CC BY 4.0 (logo/trademark excluded) |
| African Storybook | levelled early readers, African languages | CC BY 4.0 |

Do **not** ingest: CK-12 (BY-NC), Khan Academy (BY-NC-SA), MIT OCW (BY-NC-SA), Wikimedia text
(BY-SA), OER Commons items without a strict CC0/CC BY filter, and — most importantly —
**KICD curriculum designs/textbooks and KNEC past papers**, which are all-rights-reserved.
KICD's public strand/sub-strand *labels* are facts we can align to; the documents are not a
source. Generated items must never be attributed to KICD or KNEC, and every item keeps
provenance (source URLs + licence) for audit and to satisfy the Data Protection Act 2019.

**Product mismatch to resolve:** the client `Subject` enum is eight 8-4-4-style subjects,
while CBC band subjects include Integrated Science, Science & Technology, Agriculture,
Pre-Technical and Creative Arts. Non-enum subjects must ride `customSubjectName`, or the
enum gets widened client-side (a client change, so a contract decision).

Curriculum configuration must be **versioned data, not code**. Senior School's Mathematics
policy flipped between March and August 2025 (dropped, then reinstated as compulsory), and
bands/pathways/subjects will keep moving, so the band/pathway/subject catalogue belongs in a
versioned table that content is tagged against.

### 5.1 Seed corpus sizing

- **Day-one target:** ~850 topics / ~8,500 items / ~850 micro-lessons across roughly 70
  subject-grade shelves (14 grades × ~5 core subjects × ~12 topics).
- **Tier 1 (launch, exam-critical):** G4–G9 core five — Mathematics, English, Kiswahili,
  Integrated Science / Science & Technology, Social Studies — about 30 shelves, 12–20 topics
  each (~480 topics, ~4,800 items).
- **Tier 2:** PP1–G3 literacy / numeracy / environmental, then the G10 pathway core.
- **Per topic:** one micro-lesson (concept + worked example), 8–10 scored items (8 is the
  floor for a quiz to feel non-trivial), one flashcard set, plus one strand-level mixed quiz.
- **Depth pass:** 20 items/topic with difficulty variants → ~15k–18k items.

---

## 6. Moderation and quality gates

- Nothing ships unmoderated (§13.5): an automated safety/quality pass plus a human-in-the-loop
  queue for anything flagged. The batch seed goes through the same gate.
- Validators before the moderator: JSON-schema validity, answer-key correctness (solve the
  question independently), curriculum alignment (topic is in the skeleton), duplication against
  the cache, and markdown/SVG sanitisation.
- Safety filter (implemented, 7.5d): a deterministic, fail-closed `SafetyValidator` runs first in
  the validator chain and scans the teachable prose (unit, step and question text, options, answer
  keys and explanations) against the versioned `safety/blocklist-v1.json` categories - sexual
  content involving minors, explicit sexual content, self-harm, graphic violence, hate,
  dangerous instructions and personal data. A match is a BLOCKER, so the unit scores 0.0, cannot
  auto-approve, and is routed to the human exception queue; a missing or unparseable blocklist
  also blocks. It is not a policy toggle: safety is always on.
- Independent answer-key verification (implemented, 7.5f): the structural answer-key check only
  proves a key exists and matches a single option, so a confident-but-wrong key used to pass. The
  router now runs a second, separate model interaction that solves each question from its stem
  and options alone - never from the stored key - and records the agreement ratio on the unit.
  An assessment auto-approves only when every key agrees (policy `answer_key_min_agreement`,
  default 1.0); a disagreement, a dropped answer or an unavailable verifier fails closed into the
  human exception queue. Re-projection reuses the stored verification and spends no second call.
- Assessment generation quality (implemented, 7.5g): measured on the live provider, five real
  Grade 4 Mathematics `QUIZ` jobs all generated but none auto-approved - four were withheld with
  `ANSWER_KEY_MISSING` because the model omitted `correctAnswer` on many questions, and one with
  `STRUCTURE_STEPS_TOO_FEW` because the lesson rule ("at least three explained steps") was wrongly
  applied to a questions-only quiz. Both were defects, not model limits. The structure rule is now
  task-type aware: a titled lesson/readable unit (NOTES/BOOK/CHUNK, or anything that is not an
  assessment) still needs three explained steps, while an assessment (QUIZ/EXAM/ASSESSMENT)
  requires no minimum steps and may carry only questions; the no-questions warning and the
  final-step warning are unchanged. The generation system prompt now makes the answer key
  mandatory and self-consistent - every question object must carry a non-null `correctAnswer`,
  multiple-choice/true-false keys must be copied exactly from one of the options, and a QUIZ must
  return 8-12 questions - while the strict-JSON-only instruction is kept. There is no internal
  retry: the deterministic validators remain the gate, so a still-imperfect response keeps
  routing to the human exception queue.
- Disputed-key disposition (implemented, 7.5h): measured on the live provider, five real Grade 4
  Mathematics `QUIZ` jobs after 7.5g were all structurally valid with 10 questions and a clean
  validator score, yet only one auto-approved because the independent verifier's agreement was
  0.8, 0.9, 0.9 and 1.0. A whole-unit 1.0 bar threw away an accurate quiz over one or two bad
  items. The disposition is now per question: the disputed items are dropped from the unit, the
  survivors become the quiz, and the unit publishes only when at least one question survives,
  every surviving key agrees and the assessment floor is still met. Quality is unchanged - no
  wrong key can ship - and a quiz is not discarded over a single item; an all-disputed unit
  records 0.0 and fails closed. The policy key is `answer_key_drop_disagreements` (default true);
  set it false to keep the old whole-unit agreement ratio with no deletion.

---

## 7. Cost, latency, storage, observability

- **Cache-first**; generation is the exception, not the request path.
- Track provider/model, prompt version, tokens and cost per generation; expose them on the job.
- Prefer many small structured generations over one large "textbook", so a failure is cheap and
  partial results are reusable.
- Store text/JSON in the DB; render PDFs (readable materials) lazily and cache them, matching
  the existing report pipeline; never store video.

---

## 8. Suggested phasing

0. **7.0 Delivery contract alignment** (§1.6): fix the existing endpoint/model mismatches —
   materials field names, hub `subject`/`status`/`metadata`/`postId`, past-paper listing,
   homework submit path, doubt bookmark/return bodies, and the progress payloads — so generated
   content can actually render in staging/prod.
1. **7.1 Schema + taxonomy**: content-cache table, taxonomy tables (`cbc_substrands`/`topics`),
   generation-request key, moderation states, versioned band/pathway catalogue.
2. **7.2 Router + provider abstraction**: cache-first lookup, one LLM provider behind an
   interface, idempotent jobs, token accounting.
3. **7.3 One domain end to end**: Mathematics NOTES + QUIZ + FLASHCARDS delivered through
   `GET /learning/post/{id}/content` and rendered by the real client.
4. **7.4 Moderation + observability**: validator chain, human queue, audit trail.
5. **7.5 Seed batch**: run Tier 1 for the priority grade band and subjects; verify the rails.
6. **7.6 Expand**: past papers + marking schemes, readable study guides, homework/exam/project
   and doubt generation, remaining subjects.

---

## 9. Decisions this needs from you

1. **Which learners first.** The research recommends G4–G9 core five subjects for launch
   (~480 topics). Confirm the band, and decide the `Subject` enum question above.
2. **Curated OER vs generation-only** for the starter library. Curated CC BY textbooks are
   higher quality and give real attribution, but add a licence-review step; the permissive
   set is OpenStax / Siyavula-legacy / PhET / African Storybook.
3. **Answer-key policy for hub quizzes**: instant offline feedback (key delivered) vs
   server-graded (key withheld). The client supports both.
4. **Human moderators at launch**, or accept a stricter automated gate + sampled review.
5. **House byline** ("BrainBox Study Team") vs clearly labelled AI-generated authorship.
6. **Past-paper sourcing.** KNEC papers are copyrighted, so Tier 1 past papers must either be
   *generated in the KNEC style* (difficulty-calibrated, never claimed to be real papers) or
   licensed from a rights holder. Confirm which.
