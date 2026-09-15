# Phase 7 — Agentic Content Architecture (draft)

**Status:** draft, transcribed from the hand-drawn proposal so client and backend can be
adjusted together once refined. Companion to `PHASE7_CONTENT_RESEARCH.md` (what the content is)
and `ROADMAP.md` Phase 7 (the delivery checklist).

---

## 1. The proposal, transcribed

Title: *high level Agentic training hub / E-learning hub design proposal.*

| Diagram element | Reads as |
| --- | --- |
| **Task Loop Engine** (top) | The orchestrator; the loop that drives a task to acceptance. |
| **Library 1: prompt 1 / prompt 2 / …** | A prompt library, handed to agents *via router*. |
| **Subject A / B / C / … / N agent** | A family of per-subject agents. |
| **Model** (bottom-left) | The LLM(s) the agents call. |
| **MCP client** | The tool/transport layer between agents and the model/tools. |
| **Critique / Reviewer (part of Task Loop Engine)** | The critic; returns *output feedback* to the loop. |
| **Human Reviewer** | Human-in-the-loop gate beside the critic. |
| **Library 2: Exam hub DB — private / public** | Generated/published exams, split by visibility. |
| **Api click** (API call) | The delivery/read path into the app. |
| **Generate personalised Exam papers / real-time web scraping / from web intel** | Assessment generation grounded on live web intelligence. |
| **subject Notes & Guides DB** | The canonical notes/guides store. |
| **Exam Templates & Resources** | Paper blueprints and reusable resources. |
| **web scraping** | A grounding tool. |
| **learning hub** | The learner-facing consumer surface. |

Additional input: a **separate, locally-served web console** for human-in-the-loop review and
platform-wide moderation. That is a new app, distinct from the Android client and from
`BrainboxWeb` (the teacher workspace SPA).

---

## 2. Refined component model

### 2.0 The Router — the interception and capture plane (the piece that matters most)

The router is not a lookup helper. It is the **single choke point that every piece of agentic
work passes through**, in both directions:

- **North-south (app → backend):** a content request arrives; the router answers it from the
  cache or admits it as a generation job.
- **East-west (agent → model / tools):** every LLM call and every MCP tool call a subject agent
  makes is *intercepted* by the router rather than called directly.

Because nothing bypasses it, this is where four things happen that cannot be bolted on later:

1. **Capture.** Every call is recorded as an `agent_run` + `model_call` + `tool_call` with
   prompt version, model, messages, parameters, raw response, tokens, cost, latency, cache
   verdict and outcome (approved / revised / escalated / failed). This is the agentic audit
   trail and the substrate for evaluation, replay and dataset building.
2. **Route.** Cache hit vs generate; which subject agent and prompt version; which model
   provider (cost / latency / availability); which tool. The diagram's "agents via router".
3. **Enforce.** Policy before spend: licence allow-list, safety pre-checks, budget and rate
   caps, and "never reproduce restricted sources".
4. **Serve.** On a hit it returns the stored unit; on a miss it opens the job and either holds
   the client or streams progress.

Two consequences: the moderation console's job monitor and the token/cost dashboard are
**views over the router's capture tables**, not separate plumbing; and a request must be able
to carry a **"bypass cache / re-generate"** flag (editor/admin only) with the reason logged.

`ARCHITECTURE.md` §13.2 already calls the Router the app-facing entry point. It is both the
app-facing entry point and the agent-facing interception layer — for one deployment, the same
component rather than two.

### 2.1 Task Loop Engine — the supervisor

One durable job per content request: `reason → generate → critique → revise → (escalate) →
store`. It must be **bounded and resumable**: a max-iteration and cost ceiling, an acceptance
criterion per task type, and persisted state so a crash resumes rather than restarts. It owns
the task type (hub book, readable chunk, quiz, past paper, homework), loads the concept +
curriculum mapping + learner context, selects the subject agent, routes a versioned prompt, and
decides when to escalate to a human.

**Tier 1 batch producer (7.5e).** The one-command / one-call producer (`ContentBatchService`,
`ContentBatchBootstrap`, `POST /admin/content/batch`) is a queue client, not a second execution
path: it never calls the provider and never writes a capture or client-facing table. It resolves the
seeded Tier 0 topics and enqueues deterministic `GenerationRequest`s through the durable
`generation_jobs` queue using the same idempotent per-key contract, so everything it submits runs the
router → worker → projection/auto-approval path and is captured and gated exactly like on-demand
content. A batch run at breadth therefore adds no new bypass; it only fills the queue.

### 2.1.1 Task-loop placement — the three options

| | **A. Tightly coupled** (in-process module) | **B. Internal, decoupled runtime** (same project, own process) | **C. External** (third-party / separate codebase) |
| --- | --- | --- | --- |
| Shape | Spring beans in the API JVM; the router is a bean | Same repo, same contracts, own deployable(s), shares DB/queue | Outside the project boundary |
| Build speed | **fastest** | medium | fastest to prototype |
| Ops burden | **lowest** (one artifact) | medium (a second deployable, config, health) | low for us, vendor-managed |
| Failure isolation | poor — a generation leak/GC can starve the API | **good** | good |
| Scaling | all-or-nothing (scale the API to scale agents) | **independent workers** | vendor |
| Release cadence | agent change = API redeploy | **independent** | independent |
| Language freedom | JVM only | **any runtime** (Python LLM ecosystem if wanted) | any |
| Data / IP / DPA 2019 | inside | inside | **leaves our boundary** — student data, licensing/provenance |
| Router / capture integrity | **trivial** — one bean, nothing bypasses it | needs a protocol + trace ids at the boundary | **hardest** — "nothing bypasses the router" cannot be guaranteed |
| Consistency / idempotency | single transaction boundary | distributed failure, duplicate jobs, contract versioning | same, plus vendor churn |
| Testability | one process, easy | **contract tests required** | end-to-end hard |
| Cost control | fine at low volume | fine, explicit | opaque, lock-in risk |

**Decision (locked): B, reached through A.** Build the task loop as a module with a real seam from
day one — the router interface, a **durable DB-backed job queue** (`generation_jobs`, idempotent
per key) and a worker interface — and run it **in-process first** on a dedicated executor so bulk
generation never shares threads with request serving. Because the seam exists, extracting it to a
separate worker process later is a deployment change, not a rewrite.

**Scheduled extraction:** the worker split happens at **7.5**, at the start of the seed batch —
the batch is the real bulk workload to extract against, and it lands the job/poll semantics before
the endpoints are widely used. Until then the agent runs in the API JVM (currently synchronously on
the request thread, which the split also fixes).

**Scheduled extraction — 7.5a delivered.** The split is in. A durable DB-backed queue
(`generation_jobs`, idempotent per generation key) carries the full `GenerationRequest` payload and
a per-job retry budget (`max_attempts`, `next_attempt_at`). `app.content.run-mode=api|worker|both`
decides whether a JVM enqueues, drains, or both: the worker claims the oldest eligible `QUEUED` job
(optimistic-lock protected so two workers cannot take the same row), runs it, and on failure
reschedules it with a linear backoff until the budget is spent; a reclaim pass returns `RUNNING`
jobs untouched past `stale_run_seconds` to `QUEUED` so a crashed worker cannot strand work. The
HTTP surface is submit then poll (`POST teacher/content/generate`,
`GET teacher/content/jobs/{jobId}`; `QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`). The router remains the
sole provider caller and capture writer: the synchronous `resolve` and the worker `runQueued` both
run one shared core, so capture and content persistence cannot diverge. 7.5b is the seed batch that
exercises the queue at bulk.

**Extraction triggers:** concurrent generations saturating the API executor; API latency SLO
breaches correlated with generation load; a decision to write agents in a non-JVM language; or
pre-generation batch windows large enough to need dedicated nodes.

**Scope note:** DSH and MCP are *tooling* used to build and operate this. The production runtime
that serves learners is the app plus its worker — not a developer harness, and not an external
agent platform that the content standard, capture and licensing would depend on.

### 2.2 Prompt library (Library 1)

Prompts are **versioned data**, keyed by `(taskType, subject, gradeBand, standardVersion)`, not
strings in code. The router (§2.0) selects the prompt set and records its version and eval score
on every generation so quality is attributable.

### 2.3 Subject agents — concept × subject × pedagogy

The diagram's Subject A..N map to sub-agents, but the BrainBox standard means each is really
*concept + subject + pedagogy*: the same concept taught the BrainBox way. Expect dedicated
roles beyond subjects — a **diagram/figure agent** (labelled SVG drawings), a **localisation
agent** (examples, names, money, language), and an **assessment agent** (nested questions,
past papers). All are stateless workers the loop can call in parallel.

### 2.3.1 The concept layer (locked: build now, Kenya as first mapping)

The shared concept/topic taxonomy is built **now**, with Kenya CBC as its **first mapping**
rather than the schema itself. `concept` is the stable key that content, the cache and
promotion hang off; each country is a `curriculum_map` that links its strands, sub-strands and
learning outcomes to concepts. The existing `cbc_strands` catalogue is **folded in as Kenya's
strand-level mapping**, not kept as a second taxonomy — one canonical layer, many mappings.

Why now: retrofitting later changes every generated item's key and invalidates the cache
wholesale, and re-localisation (one concept taught in another country's context) is the whole
"standard for Africa" story. The cost now is one extra table and a mapping step in the router.

### 2.4 Model providers

A provider interface with cost/latency routing (DeepSeek first). The Spring AI DeepSeek starter
is already on the classpath but auto-config is excluded; token/cost accounting per generation is
required (§13.5).

### 2.5 MCP tool layer (locked: one client, in-process tools)

The MCP client is the right abstraction, and for now the tools live **in-process, registered
behind a single MCP client** rather than as self-hosted MCP servers per store. The router already
provides the interception and capture point, so a per-store server fleet would add deployment
surface before there is a second consumer. Expose: curriculum/concept lookup, Notes & Guides
read/write, exam templates, learner mastery/metrics, web fetch/scrape, diagram rendering, and the
validator suite.

**Extraction trigger:** when a second consumer appears — the Brainbox console, or a non-JVM agent —
promote the same registrations to real MCP servers behind the same client, so the tool contract
does not change.

### 2.6 Quality and safety — validators, critic and two moderation tiers

**Quality** is two layers: a deterministic validator chain (schema, reading level, step length,
visual presence, nested-question cadence, answer-key correctness, duplication, licensing) and an
LLM critic for pedagogy. Both return structured findings the loop acts on; a hard-fail escalates.

**Safety has two tiers, because the latency budgets differ:**

- **Real-time tier (route-time).** Personalised quizzes and anything a learner waits on pass a
  **synchronous, rule-based filter** before leaving the router: deny-lists and pattern rules for
  profanity, sexual/violent content, unsafe advice, politics, off-topic and jailbreak artefacts,
  plus PII checks. Cheap, deterministic and **fail-closed** — if the filter errors, the content
  is withheld. A model-based classifier replaces or augments it later behind the same seam.
- **Bulk tier (write-time).** Larger content written into the client-facing tables (books,
  chunks, papers, homework) is tagged **`REVIEWED` / `UNREVIEWED`**; the moderator console
  toggles the tag to confirm. Only `REVIEWED` content is learner-visible.

### 2.6.1 Validators and confidence auto-approval (delivered, 7.4b; machine-first in 7.5c)

The deterministic half of quality is implemented as a chain of `ContentValidator` beans
(`structure`, `questions`, `answer_key`, `curriculum`, `language`).
`ContentValidationService.validate(contentType, contentId)` runs all of them over one content
version and aggregates their findings into a `ValidationReport`:

- `BLOCKER` forces the score to 0.0 and sets `blockers = true`; otherwise the score is
  `1.0 - sum(penalty)` (BLOCKER 1.0, WARNING 0.1, INFO 0.02), clamped to 0..1. A blocked item can
  therefore never clear an auto-approval threshold.
- Structure is task-type aware (7.5g): every unit requires a title and every step that exists
  requires a non-blank body, but the three-explained-steps minimum is a lesson rule. A lesson or
  readable unit (NOTES/BOOK/CHUNK, and anything that is not an assessment) still needs at least
  three steps; an assessment (`QUIZ`/`EXAM`/`ASSESSMENT`) is measured by its questions, so a quiz
  is not a lesson and may carry zero lesson steps and only questions. A unit with no questions still
  gets the `STRUCTURE_NO_QUESTIONS` warning, and the final-step question warning is a lesson rule
  (7.5i): it is emitted only for a non-assessment unit whose final existing step has no attached
  question, because a quiz's questions need not be attached to a step. Questions require a prompt
  and, for multiple choice, at least two distinct options;
  the answer-key check requires a key that matches exactly one option; curriculum requires a
  resolved concept and a mapping; language requires a language tag and teachable text.

**Safety is the first hard gate (delivered, 7.5d).** Before any quality signal is trusted, every
unit passes `SafetyValidator` (name `safety`), a **deterministic, non-LLM** filter that scans the
unit title and body, every step title and body, and every question text, option, correct answer
and explanation. It deliberately does not scan `figure_svg`, which is renderer markup rather than
teachable prose. The patterns are **versioned resource data, not code**
(`src/main/resources/safety/blocklist-v1.json`), loaded and compiled once at startup; the stable
categories are `SAFETY_SEXUAL_MINORS`, `SAFETY_EXPLICIT_SEXUAL`, `SAFETY_SELF_HARM`,
`SAFETY_VIOLENCE_GRAPHIC`, `SAFETY_HATE`, `SAFETY_DANGEROUS_INSTRUCTIONS` and
`SAFETY_PERSONAL_DATA` (Kenyan phone numbers, email addresses and national-ID patterns). A match
is always a `BLOCKER`, so `ContentValidationService` forces score 0.0 and `blockers = true` and
the 7.5c bar refuses the unit: it is withheld and appears in the human **exception queue** rather
than reaching a learner. The gate is **fail-closed and always on** (there is no policy toggle): if
the blocklist resource is missing, blank or unparseable the validator emits the
`SAFETY_CONFIG_MISSING` blocker for every unit, so nothing auto-approves. A false positive costs a
human review; a false negative cannot silently pass.

**Independent answer-key verification is a hard gate ahead of auto-approval (delivered,
7.5f).** The deterministic `answer_key` validator is only structural: it checks that a key
exists and, for multiple choice, that it matches exactly one option. A present-but-wrong
key passes it. So before an assessment can auto-approve, the router runs a second, separate
model interaction (`ContentRouter.verifyAnswerKeys`) that solves each question from its
stem and options alone - the request never carries the stored `correctAnswer`, and the
call is captured as its own `agent_runs` + `model_calls` pair through the router. The
returned answers are compared with the stored keys under a forgiving-but-safe normalisation:
case, whitespace and surrounding punctuation are ignored, and for multiple choice an
`A`/`B`/`C`/`D` letter or a 1-based option number is resolved to the option text first.
The agreement (`agreements / questions`) is stored on the unit as `answer_key_agreement`,
`answer_key_verified_at` and `answer_key_verified_model` (V68). The gate is fail-closed:
a disabled provider, an error, a malformed response or a dropped answer leaves the unit
unverified. An assessment with questions auto-approves only when `answer_key_verified_at`
is set and `answer_key_agreement >= answer_key_min_agreement`; anything else stays
`UNREVIEWED` in the exception queue. Verification runs once per generated unit and
re-projection (including a human approval) reuses the stored result, so no second model
call is spent. Non-assessment units are not subject to this gate.

**Per-question disposition of disputed keys (delivered, 7.5h).** Measured on the live provider,
five Grade 4 Mathematics `QUIZ` jobs after 7.5g were structurally valid with 10 questions and a
clean validator score, but only one auto-approved: the independent verifier returned agreement
0.8, 0.9, 0.9 and 1.0, and the whole-unit 1.0 bar discarded an otherwise accurate quiz over one or
two bad items. The disposition is now per question. When `answer_key_drop_disagreements` is true
(the default), every question whose independent answer did not agree - including a dropped/blank
answer or a missing stored key - is deleted from `content_unit_questions`, and the unit records
the count in `answer_key_dropped` and a JSON audit of `{orderIndex, text, storedKey,
verifiedAnswer}` per removed item in `answer_key_dropped_detail` (V69). The agreement becomes
1.0 when at least one question survives and 0.0 when none does, and the deletion commits in the
same transaction as the `agent_runs` + `model_calls` capture, so the capture and the
disposition can never diverge. Every surviving key therefore agrees, so no wrong key can ship,
while the question floor still decides whether the quiz is complete enough to publish. When the
policy is false, nothing is deleted and the legacy `agreements / questions` ratio applies.
Idempotency is unchanged: an already-verified unit returns its stored agreement (even 0.0 after
an all-disputed run) and spends no further model call.

**Auto-approval is the default bulk path (delivered, 7.5c).** The rule is machine-first,
human-for-exceptions: `AutoApprovalService.maybeAutoApprove` approves a UNIT without a human
whenever every gate holds, so teachers and Brainbox moderators only ever handle the exceptions
(anything that fails a gate), and every human can still override or unpublish. The machine bar is:

- `auto_approve_enabled` (boolean, default **true**): the feature switch; the console can still
  turn the machine off for a deployment.
- `auto_approve_min_validator_score` (double, default **1.0**): the report must have no blockers
  and score at least this; 1.0 means zero findings (no warnings or infos).
- `auto_approve_min_critic_confidence` (double, default **0.90**): when a unit carries any
  questions the model confidence must be non-null and at least this; a null confidence fails
  closed.
- `auto_approve_min_questions` (int, default **8**): applies to **assessment task types**
  (`QUIZ`/`EXAM`/`ASSESSMENT`) that carry questions, where the question count is the product. A
  `NOTES`/readable micro-lesson may carry a few nested checks (the BrainBox standard wants them)
  and is not held to the floor; it is still bound by the validator-score and confidence gates.
- `answer_key_min_agreement` (double, default **1.0**): the independent answer-key floor for an
  assessment task type with questions. 1.0 means every stored key must agree with the independent
  solve; an unverified unit fails closed.
- `answer_key_drop_disagreements` (boolean, default **true**): the 7.5h per-question disposition.
  True drops each disputed question and requires the surviving keys (and the floor) to pass; false
  keeps the whole-unit `agreements / questions` ratio with no deletion.

The assessment question floor is now unconditional (7.5h): because a unit can end with zero
questions when every disputed item was dropped, `auto_approve_min_questions` is checked before
anything else for `QUIZ`/`EXAM`/`ASSESSMENT`, so a zero-question assessment fails the gate
instead of skipping it. A non-assessment unit with questions is still held to the confidence gate
but never to the question floor.

The unit must also still be `UNREVIEWED`: the machine never touches a `REVIEWED` or `REJECTED`
unit, so a human decision and its reviewer attribution are never clobbered. On approval the unit
becomes `REVIEWED` and a `moderation_outcomes` row is upserted with `auto_approved = true`,
`confidence_score` = the validator score and a null `reviewer_id`, so a machine decision is never
mistakable for a human one. Any later human decision clears the marker. The old
`auto_approve_threshold` key and the per-domain floors are gone; the single validator-score bar
replaces them, and `weighted_approvals` is still captured on the outcome for analytics but never
drives a decision.

**From gate to learner visibility.** The generation worker projects every freshly generated unit
(`ContentProjectionService.project`) right after the job produces it: a clean unit is
auto-approved and published, anything that fails a gate is written hidden (`DRAFT`,
`isPublished = false`) and stays in the exception queue. Projection always runs the gate before it
reads `reviewState`. A human approval via the review surface re-projects the resolved `REVIEWED`
unit, so approving a previously hidden exception makes it learner-visible.

The teacher surface is `GET /teacher/review/queue`,
`GET /teacher/review/{contentType}/{contentId}`, `POST .../decision`, `GET .../decisions` and
`POST /teacher/content/feedback`. Quorum remains two distinct teacher approvals and one reject
resolves immediately, as locked in §2.7; the human path always wins over the machine.

### 2.7 Write-through, the human reviewer and the teacher feedback loop

The HITL gate is `GENERATED → AUTO_REVIEW → REVIEWED | REJECTED`, with reviewer identity,
comments, side-by-side diff and rollback, served by the local console (§5). Three rules:

- **Write-through.** Whatever the model generates is written into the *specific* table the client
  already calls (notes/guides, exam hub, readable materials) with its moderation state and
  provenance — no side store, no separate sync job.
- **Reviewed-only reads.** Those same tables are read by the client, so **the learner-facing read
  endpoints must be modified to return only `REVIEWED` rows**. `UNREVIEWED` model content is
  visible to teachers (so they can rate it) and moderators, never to learners. Existing and
  teacher-uploaded rows are backfilled `REVIEWED` so nothing disappears; the model writer inserts
  `UNREVIEWED`. The filter applies to every learner read whose table the model writes to —
  learning posts/content, readable materials, past papers/exams — and is part of the 7.0 contract
  work, not an afterthought.
- **One exception.** The real-time personalised path (§3.A2) is generated per request and returned
  after the synchronous rule filter instead of being read from the shared corpus, so it is the
  only `UNREVIEWED` content a learner sees.
- **Provenance attribute.** Anything fully model-authored carries `generated = true` (plus
  author/created-by and source URLs), and the client shows it, so a teacher always knows what
  the model wrote versus what a human did.
- **Teacher feedback.** Teachers can **rate** model materials and flag what they dislike. A
  rating is stored against the exact generation — prompt version, model, run id from the router
  (§2.0) — so the aggregate ("what most teachers do / do not want") becomes the preference
  signal that steers prompt selection and future model choice.

**Moderation is distributed, not only central.** Teachers receive *all* content — reviewed and
unreviewed — because they must browse the paper catalogue to assign paper-review homework, and
because a teacher is a moderator: unreviewed model content can be opened and reviewed **from the
client** (a client feature to add), and any model content, reviewed or not, can be rated. The
moderator console is the backstop for adjudication, appeals and platform-wide moderation rather
than the only review door.

**Moderation policy is configuration, not code.** The default published rule is **two approvals
from any teacher of the matching subject/grade**. The policy is a versioned config the Brainbox
moderator can switch at any time, with modes for weighted expertise (coordinator /
subject-expert approvals count more), moderator-only, and **confidence auto-approval**. A
switch applies to new reviews; existing decisions stand.

**Confidence scoring drives auto-approval.** The critique/reviewer emits a calibrated
confidence score per item; 7.5c makes that score a **default-on** part of the machine bar
(`auto_approve_min_critic_confidence`, default 0.90, fail-closed when absent) alongside the
validator-score and question-count gates in §2.6.1. The longer-term tightening is per
`(subject, grade, task)`: a domain's measured accuracy bar of 99%, measured **per dimension**
rather than blended (safety block recall, answer-key correctness, schema/curriculum validity,
teacher-acceptance rate), can raise the shared policy. An eval harness over a golden set gates
that per-domain tightening.

**Trust tiers exist from day one, and only ever add weight.** A teacher's tier rises with
review volume and **agreement** — their rating/decision matching the eventual consensus
exactly or within a tolerance. Higher tiers can have their approval count for more. Two
constraints: **Brainbox moderators are excluded** from this ladder (they are the backstop, not
teachers on it), and **tiers never gate eligibility** — a teacher who has never reviewed can
always start; trust only changes how much their review counts.

**Promotion gate (locked).** A personalised paper is never promoted as an instance. What
graduates is its **new questions and its blueprint**, and only when all of these hold:

- every question is `REVIEWED` or confidence-auto-approved above threshold;
- ≥3 ratings from ≥2 distinct teachers, mean ≥4/5, no "wrong answer" or "unsafe" tags, and fewer
  than 20% of ratings at ≤2 (no polarised content);
- at least one reuse by a teacher **other than** the one whose learner triggered it (independent
  validation, and impossible to reach by a single teacher assigning repeatedly);
- safety clear, and a PII / learner-specificity check passes — no learner names or data inside
  the promoted items.

Promotion snapshots its evidence and is **reversible**: a later qualifying negative signal
demotes the item and reopens it for review. Every threshold lives in the moderation policy, so
it can be tightened or loosened without a deploy.

### 2.8 Data stores

- **Subject Notes & Guides DB** — the canonical learning units (concept-first, versioned).
  This doubles as the **generation cache**: unique per `(taskType, concept, grade, standard,
  schemaVersion, promptVersion)` so identical requests are idempotent.
- **Library 2 / Exam hub DB (private vs public)** — `private` = generated / `UNREVIEWED`,
  `public` = `REVIEWED` and learner-visible. Same split applies to notes, guides and chunks.
- **Exam Templates & Resources** — paper blueprints (subject, grade, sections, marks, duration)
  and reusable resource assets.
- Every row carries its **review state** (`UNREVIEWED` / `REVIEWED` / `REJECTED`) and
  **provenance**: `generated` vs `uploaded`, author/created-by, source URLs, licence, prompt
  version, model, tokens, moderation outcome.
- **`content_feedback`** — teacher ratings/flags joined to the generation (prompt, model, run id),
  the preference dataset that steers the pipeline.

### 2.9 Web intel / scraping — grounding with rules

Real-time scraping is how "personalised" papers get current context, but it is also the biggest
legal risk. Scraped material is **grounding only**: allow-list sources, record every URL and
licence, block the restricted set (KICD, KNEC, CC BY-NC/SA), and never reproduce text verbatim.

### 2.10 Delivery

Public store → existing client surfaces (`learning/post/{id}/content`, `materials/readable/{id}`,
`past-papers/{examId}/content`) via the API-call path, with the 7.0 contract fixes applied first.

**Read-path caching is origin-side (H1).** There is no third-party CDN in the Brainbox
deployment (data sovereignty), so those three reads are cached at the origin: each returns a
body-derived strong `ETag` with `Cache-Control: max-age=60, must-revalidate, private` and a
matching `If-None-Match` gets `304 Not Modified`. The pipeline also exposes real Micrometer
metrics on the existing `MeterRegistry` for queue depth, provider latency and errors, token
spend and the auto-approval rate (see ROADMAP Phase 6 H1).

---

## 3. End-to-end flows

**A. Hub book or readable chunk.** Task loop resolves concept → subject agent generates a
learning unit (steps with nested questions + figures) → validators + critic → revise → human
review → public store. A chunk is the same unit projected short; a book is the full unit.

**A2. Real-time personalised quiz.** The learner asks for practice; the router admits the job,
returns the generated quiz **after the synchronous rule filter only** (no human wait), and stores
it tagged `UNREVIEWED` — a later moderator pass promotes or pulls it. Bulk content takes the full
queue; the latency budget is what separates the two.

**B. Quiz / flashcards.** Same unit, projected as an assessment block; answer keys stored
separately and stripped for hub delivery, kept for past papers.

**C. Personalised exam paper.** Exam template + learner weak topics (mastery) + web intel →
assessment agent → critique → human review → Exam hub `private`, then `public`.

**D. Teacher upload.** Existing `teacher/content/document` path → same stores, `uploaded`
provenance, bypasses generation but not moderation.

---

## 4. Client and backend adjustments this implies

- **Backend schema:** the router capture tables (`agent_runs`, `model_calls`, `tool_calls`,
  cache keys), `concepts`, `curriculum_map` (per-country), `learning_units` + `steps` +
  `unit_questions` + `unit_figures`, `question_bank` (reviewed, versioned, tagged),
  `paper_blueprints` + `paper_instances`, `generation_jobs`, `prompt_versions`, `content_reviews`
  (reviewed/unreviewed), `content_feedback` (teacher ratings), `reviewer_trust`, `moderation_policies`,
  `eval_examples`/`eval_runs` (the golden set and measured accuracy), `provenance`/licence columns,
  and private/public state on all content.
- **Backend delivery:** the §1.6 contract fixes (materials body, hub `postId`/`metadata`/`status`,
  enum subject, doubt, progress), a **reviewed-only filter** on every learner read over a
  model-written table, and a **rich chunk body** so visual chunks are not plain text.
- **Client:** the existing `LearningContent` block order already supports nested questions if the
  unit projects each step as a block; long-term the client wants a first-class *step* + *figure*
  rendering path rather than markdown-only notes. No client change blocks 7.0.

---

## 5. The Brainbox team console (internal web app — ops, administration and moderation)

A new app — not the Android client, not `BrainboxWeb` (the teacher workspace) — served
locally/internal. It is the **Brainbox staff screen**, so moderation is only one part of it:

- **Platform analytics:** total and active users, schools, signups/growth, engagement, content
  volume and review throughput, and the all-platform view.
- **Account and school administration (platform level):** suspend / unfreeze **any** account,
  manage school admins, and the platform-wide actions behind the admin API. School admins keep
  their own **school-scoped** freeze/unfreeze; the console is the platform-level capability.
- **Content review (HITL):** the queue with side-by-side diffs, the Reviewed/Unreviewed toggle,
  approve/reject/request-changes, publish / unpublish / rollback, and promotion overrides.
- **Teacher feedback:** ratings aggregated per concept, prompt version and model — what teachers
  want and do not want.
- **Prompt, curriculum and policy management:** versions, eval scores, and the moderation policy
  switch (quorum size, weighted experts, auto-approve).
- **Platform-wide moderation** of user-generated surfaces (CBC projects, doubt, class chat, news).
- **Generation monitoring:** traces, tokens, cost, failure/escalation reasons.

It shares the backend API and the MCP tool surface, so it is a frontend, not a second backend.
Much of the administration (school analytics, approvals, system settings, audit logs, backups)
already exists from Phase 5 (`api_admin_changes.md`); the console surfaces it and adds the
moderation/generation screens.

---

## 6. Decisions — settled and still open

**Settled in discussion:**

- **Task-loop placement (locked): option B reached through A.** An internal, decoupled runtime —
  the router as the only egress, a durable DB-backed job queue, idempotent jobs and a worker
  interface with its own executor — run in-process first, extracted to a separate worker when the
  triggers in §2.1.1 fire.
- **MCP (locked):** one MCP client with in-process tool registrations; promote to self-hosted MCP
  servers only when a second consumer (the console or a non-JVM agent) needs them.
- **Concept layer (locked):** the shared concept → per-country curriculum mapping is built now,
  with Kenya CBC as the first mapping; `cbc_strands` is folded in as Kenya's mapping rather than
  kept as a parallel taxonomy.
- **Languages (locked):** English + Kiswahili at launch; `language` is first-class on units and
  chunks so more languages are rows, not schema changes; the localisation agent adds them later
  (mother-tongue early-grade readers are Tier 2).
- **Accuracy targets (locked):** auto-approval opens per `(subject, grade, task)` only when the
  domain clears schema/curriculum 100%, answer-key ≥99.5% (100% on the auto-approve set), safety
  recall ≥99% (fail-closed), source-attributed grounding, and teacher acceptance ≥90%. The golden
  set starts at 200–500 items (teacher decisions plus a curated safety set) and grows from usage.
  Reviewer agreement means decision match or rating within ±1; a coordinator counts 2 toward a
  quorum of 3, but a quorum always needs at least two distinct humans.
- **Personalised papers:** both paths — learner practice (real-time, rule-filtered, visible to
  both the teacher and the linked parent) and teacher-assigned (through review, assigned as
  paper-review homework).
- **Assemble-first:** papers are assembled from a **reviewed question bank** by blueprint plus the
  learner's weak topics; generation fills gaps only. This keeps review load tractable and makes
  the bank the compounding asset.
- **Auto-promotion:** a personalised paper that performs well is published back automatically;
  promotion **generalises** it (learner-specific ordering and data stripped) before it enters the
  shared bank, and a moderator can still demote it.
- **Account authority:** school admins keep their **school-scoped** freeze/unfreeze; the
  Brainbox console holds the **platform-level** suspend/unfreeze for any account.
- **Promotion gate (locked):** new questions and the blueprint graduate — not the learner-specific
  paper instance — through the evidence gate in §2.7 (reviewed, ≥3 ratings from ≥2 teachers, mean
  ≥4/5, independent reuse, safety/PII clear), snapshotted and reversible.
- **Brainbox team console scope:** platform analytics, platform-level account/school
  administration, moderation, prompts/policy and generation monitoring — one internal app.
- **Approval authority:** default quorum = **two approvals from any teacher of the matching
  subject/grade**. A versioned policy the Brainbox moderator can switch to weighted-expertise or
  moderator-only at any time; the switch applies to new reviews.
- **Teacher visibility:** teachers receive everything, reviewed and unreviewed — needed to assign
  paper-review homework and to moderate; learners receive reviewed only.
- **Auto-approval:** machine-first and **on by default** (7.5c): a unit auto-approves when it
  clears the validator-score (1.0), question-count (8) and critic-confidence (0.90) gates and is
  still UNREVIEWED; the four keys are documented in §2.6.1 and the human path always overrides.
- **Trust tiers:** from day one, agreement-based, **weight-only**; Brainbox moderators excluded;
  a teacher who has never reviewed is never blocked.
- **Per-version reviews:** a review binds to a content version; a regeneration resets to
  unreviewed.
- **Offline reviews:** approvals are online-only and version-checked (ratings may queue).
- **Rating shape:** 1–5 plus actionable tags ("clear", "too hard", "wrong answer", "needs
  diagram"), joined to the prompt/model version; the review decision and the rating stay separate
  signals.

**Still open:** none at the architecture level. Everything outstanding is either measured (the
accuracy numbers, once the eval harness runs) or a build task.
