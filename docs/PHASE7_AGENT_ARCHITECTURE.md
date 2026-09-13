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

### 2.1 Task Loop Engine — the supervisor

One durable job per content request: `reason → generate → critique → revise → (escalate) →
store`. It must be **bounded and resumable**: a max-iteration and cost ceiling, an acceptance
criterion per task type, and persisted state so a crash resumes rather than restarts. It owns
the task type (hub book, readable chunk, quiz, past paper, homework), loads the concept +
curriculum mapping + learner context, selects the subject agent, routes a versioned prompt, and
decides when to escalate to a human.

### 2.2 Prompt library + router (Library 1)

Prompts are **versioned data**, keyed by `(taskType, subject, gradeBand, standardVersion)`, not
strings in code. The router picks the prompt set and the agent; prompt version and eval score
are recorded on every generation so quality is attributable.

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

### 2.6 Critique / Reviewer — validators + critic

Two layers in one stage: a **deterministic validator chain** (schema, reading level, step
length, visual presence, nested-question cadence, answer-key correctness, duplication,
licensing/provenance) and an **LLM critic** for pedagogical quality. It returns structured
findings the loop can act on; a hard-fail escalates instead of looping forever.

### 2.7 Human reviewer + moderation console

The HITL gate: `GENERATED → AUTO_REVIEW → HUMAN_REVIEW → APPROVED | REJECTED → PUBLISHED`,
with reviewer identity, comments, side-by-side diff and rollback. Served by the separate local
web console (§5).

### 2.8 Data stores

- **Subject Notes & Guides DB** — the canonical learning units (concept-first, versioned).
  This doubles as the **generation cache**: unique per `(taskType, concept, grade, standard,
  schemaVersion, promptVersion)` so identical requests are idempotent.
- **Library 2 / Exam hub DB (private vs public)** — `private` = generated/awaiting review,
  `public` = approved and learner-visible. Same split should apply to notes/guides.
- **Exam Templates & Resources** — paper blueprints (subject, grade, sections, marks, duration)
  and reusable resource assets.
- Every row carries **provenance**: `generated` vs `uploaded`, author/created-by, source URLs,
  licence, prompt version, model, tokens, moderation outcome.

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

**B. Quiz / flashcards.** Same unit, projected as an assessment block; answer keys stored
separately and stripped for hub delivery, kept for past papers.

**C. Personalised exam paper.** Exam template + learner weak topics (mastery) + web intel →
assessment agent → critique → human review → Exam hub `private`, then `public`.

**D. Teacher upload.** Existing `teacher/content/document` path → same stores, `uploaded`
provenance, bypasses generation but not moderation.

---

## 4. Client and backend adjustments this implies

- **Backend schema:** `concepts`, `curriculum_map` (per-country), `learning_units` + `steps` +
  `unit_questions` + `unit_figures`, `generation_jobs`, `generation_attempts`, `prompt_versions`,
  `moderation_reviews`, `provenance`/licence columns, and private/public state on all content.
- **Backend delivery:** the §1.6 contract fixes (materials body, hub `postId`/`metadata`/`status`,
  enum subject, doubt, progress) plus a **rich chunk body** so visual chunks are not plain text.
- **Client:** the existing `LearningContent` block order already supports nested questions if the
  unit projects each step as a block; long-term the client wants a first-class *step* + *figure*
  rendering path rather than markdown-only notes. No client change blocks 7.0.

---

## 5. The moderation & human-in-the-loop console (separate local web app)

A new app — not the Android client, not `BrainboxWeb` — served locally/internal, with:

- an authentication/role model (reviewer, subject expert, platform moderator, admin);
- the content review queue with side-by-side diffs, approve/reject/request-changes, and publish
  / unpublish / rollback;
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
