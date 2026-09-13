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

### 2.4 Model providers

A provider interface with cost/latency routing (DeepSeek first). The Spring AI DeepSeek starter
is already on the classpath but auto-config is excluded; token/cost accounting per generation is
required (§13.5).

### 2.5 MCP tool layer

The MCP client is the right abstraction for tools. Expose, as MCP tools: the curriculum/concept
lookup, subject Notes & Guides read/write, exam templates, the learner's mastery/metrics,
web fetch/scrape, diagram rendering, and the validator suite. This is also how the future
moderation console can reuse the same tool surface.

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
  `unit_questions` + `unit_figures`, `generation_jobs`, `prompt_versions`, `content_reviews`
  (reviewed/unreviewed), `content_feedback` (teacher ratings), `provenance`/licence columns, and
  private/public state on all content.
- **Backend delivery:** the §1.6 contract fixes (materials body, hub `postId`/`metadata`/`status`,
  enum subject, doubt, progress), a **reviewed-only filter** on every learner read over a
  model-written table, and a **rich chunk body** so visual chunks are not plain text.
- **Client:** the existing `LearningContent` block order already supports nested questions if the
  unit projects each step as a block; long-term the client wants a first-class *step* + *figure*
  rendering path rather than markdown-only notes. No client change blocks 7.0.

---

## 5. The moderation & human-in-the-loop console (separate local web app)

A new app — not the Android client, not `BrainboxWeb` — served locally/internal, with:

- an authentication/role model (reviewer, subject expert, platform moderator, admin);
- the content review queue with side-by-side diffs, the **Reviewed/Unreviewed** toggle,
  approve/reject/request-changes, and publish / unpublish / rollback;
- the teacher-feedback view (ratings aggregated per concept, prompt version and model), which is
  how "what most teachers want" is read back out;
- prompt and curriculum management (versions, eval scores);
- platform-wide moderation of user-generated surfaces (CBC projects, doubt, class chat, news),
  which is broader than Phase 7;
- a generation-job monitor (traces, tokens, cost, failure/escalation reasons).

It shares the backend API and the MCP tool surface, so it is a frontend, not a second backend.

---

## 6. Open questions to refine

1. Is the **Task Loop Engine** a queue + workers in the Spring app, or a separate agent service
   that calls the API? The diagram is transport-agnostic.
2. **Personalised exam papers** — are they learner-private practice papers, teacher-assigned, or
   both? That decides the private/public split and the client surface.
3. **MCP** — self-hosted MCP servers per store, or in-process tools exposed through one MCP
   client? This affects deployment and the console's reuse.
4. **Languages** — which languages beyond English/Kiswahili for the localisation agent?
5. **Moderation staffing** — who reviews, at what volume, and what is auto-approved?
6. **Every country after Kenya** — the concept layer is the plan; confirm we build it now rather
   than retrofitting after the Kenyan corpus.
