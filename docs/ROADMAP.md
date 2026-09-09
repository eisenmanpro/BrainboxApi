# BrainboxApi — Backend Roadmap & Long-Term Goals

**Repo:** /home/afrithecus/PROJECTS/BrainboxApi (Spring Boot backend for BrainBox)
**Companion repos:** BrainBox (Android client, complete), BrainboxWeb (teacher/admin web, later phase)
**Date:** 2026-09-09 · **Status:** Architecture decisions locked; Phase 1 not started

---

## 1. Mission

BrainBox is an ed-tech platform for Kenyan secondary schools (students, teachers,
parents, admins). The Android app is feature-complete and offline-first; this
repository is the server that becomes the **single source of truth**. The backend
must implement the REST contracts in the BrainBox docs, enforce every invariant the
client already assumes, and later host the agentic content-generation pipeline.

**Authoritative contracts (read these, they win):**
- BrainBox/docs/backend_contracts/ARCHITECTURE.md — platform overview, tech stack,
  feature inventory, §13 agentic content pipeline, Appendix A obligations
- BrainBox/docs/backend_contracts/01..12 — per-domain API contracts with models,
  endpoints, request/response JSON, and "Critical Server Obligations" appendices
- BrainBox/docs/backend_contracts/archive/* — older blueprints (superseded by 01..12)

---

## 2. Current Scaffold (commit 50c353e, Spring Initializr)

Already wired in build.gradle.kts:
- Spring Boot 4.1.1, Kotlin 2.3.21 (plugin.spring), Java 21 toolchain, Gradle wrapper
- Starters: webmvc, security (+ webauthn), session-jdbc, mail, actuator
- Spring AI 2.0.1 BOM: deepseek chat starter + pdf-document-reader
- tools.jackson module kotlin; configuration-processor via kapt
- PostgreSQL driver; docker-compose dev services (compose.yaml runs postgres)
- App class: com.Afrithecus.BrainboxApi.BrainboxApiApplication
- Single test: BrainboxApiApplicationTests (context loads)

Gaps to close early (Phase 1): package layout, application.yml profiles mirroring the
Android flavors (dev/staging/prod → https://dev.api.brainbox.com/,
https://staging.api.brainbox.com/, https://api.brainbox.com/), Flyway migrations,
JWT/refresh + security filter chain, global error envelope, DTO layer that matches the
Android JSON models, observability, contract tests. Pin the postgres image in
compose.yaml to a stable major (17).

---

## 3. Architecture Decisions (locked 2026-09-09)

1. **Database: PostgreSQL.** Relational integrity across exam -> questions ->
   submissions -> mastery and teacher/admin entities. Strong consistency for
   offline-first conflict resolution; window functions / CTEs / materialized views
   for analytics, leaderboards, grade distributions, attendance-performance
   correlation; JSONB for flexible content (post bodies, exam sections, question
   metadata, dynamic exam content); GIN indexes for full-text search (doubt forum,
   messages, learning hub) without a separate engine. One shared schema serves REST
   and the future web app (no sync drift).
2. **Multi-tenant scoping: RLS + app layer.** Enforce GLOBAL / SCHOOL /
   SCHOOL_GRADE_CLASS with Postgres Row-Level Security keyed on school/class
   membership where applicable, always backed by application-layer scoping checks.
   Scope inheritance comes from the requesting user context (CTC-derived).
3. **Schema alignment with the Android client.** Mirror Room entities 1:1 where
   sensible (same logical names/fields) to reduce drift and keep a future shared
   Kotlin Multiplatform model viable; uuid primary keys; updatedAt + version columns
   for offline upsert/merge and ETag/If-Match. Teacher entities share tables with
   teacherId scoping (or separate schema if conflicts arise). Offline mutation
   queues (pending_*_mutations) become Postgres tables with status + retryCount,
   consumed and acked by workers. Analytics caches become materialized views
   refreshed on demand. Server remains authoritative: it adds columns/fields that
   the client cache does not have (secrets, server-computed analytics).
4. **Data access: Spring Data JPA + Hibernate** (with explicit repository
   interfaces). Migrations own the schema; entities are persistence objects.
5. **Migrations: Flyway (SQL-first).** Versioned SQL migrations + seed/reference
   data; reviewed like code; one baseline for dev/staging/prod.
6. **Module layout: single Gradle module with domain packages**
   (auth, users, schools, exams, ... layered controller/service/repository inside
   each). Split into modules only if Phase 7 (agentic) demands isolation.
7. **JSON/DTO strategy: explicit DTO records, never entities.** Request/response
   DTOs match the documented Android JSON exactly (camelCase fields, enum strings
   such as FORM_THREE, NOVICE, GLOBAL). Bean Validation on inputs. Answer-key
   withholding and scope filtering are enforced at the endpoint/DTO layer per
   resource. Domain model + DTO mappers kept explicit.
8. **Security model: stateless JWT access + hashed refresh tokens.** 24h access
   token (doc 01), refresh tokens stored hashed in Postgres with rotation families
   and reuse detection; session registry enforces max 3 student sessions per device
   and role switching; authorities mirror roles incl. CTEACHER /
   GRADE_COORDINATOR / ICT_ADMIN; method security on services/controllers.
   spring-session-jdbc / WebAuthn starters: not on the Phase 1 path (remove from
   build when confirmed unused).
9. **API docs & contract testing: JSON fixture contract tests now, OpenAPI
   when verified.** Per-endpoint tests assert JSON shape against fixtures derived
   from the 12 contract docs, including no-answer-key and scope rules (error
   envelope already covered this way). springdoc's OpenAPI integration for Boot 4
   is still to be verified against Maven Central; add it once confirmed, else keep
   a hand-maintained openapi.yaml served statically.
10. **Complementary infrastructure (later phases, not Phase 1):**
    - Redis for JWT revocation lists, rate-limit counters, live-class participant
      counts (fallback: in-DB or in-memory until then)
    - S3-compatible object storage (MinIO locally / S3 prod) for media uploads via
      presigned URLs; Postgres stores only URLs + metadata
    - WebSocket layer for live classes (signaling), class chat, realtime updates
    - Background jobs: pg_cron or a worker service + Postgres-backed outbox for
      async reports, M-Pesa reconciliation, notification dispatch, offline-mutation
      replay
    - ML/ONNX model hosting: registry/S3, Postgres stores metadata + download state
11. **Explicitly avoided:** pure NoSQL stores, Firestore-style sync backends,
    Supabase as primary data store (custom JWT/roles/subscriptions already exist).

---

## 4. Non-Negotiable Server Obligations (enforce everywhere)

Condensed from ARCHITECTURE.md Appendix A + per-doc appendices. The client only does
UX checks; the server MUST enforce:

1. **JWT scoping** — path userId must match JWT identity on every endpoint.
2. **Content scope filtering** — GLOBAL / SCHOOL / SCHOOL_GRADE_CLASS enforced
   server-side (RLS + app layer), inherited from requesting user context.
3. **Answer-key withholding** — student-facing quiz payloads NEVER include
   correctAnswer / explanation / matchingPairs; client sends score = -1 sentinel and
   the server grades.
4. **Subscription entitlements** — every paywalled endpoint verifies active JWT tier;
   price and expiry are server-derived (client values are hints only).
5. **Teacher class scoping** — /teacher/* endpoints 403 unless the teacher owns the
   class/student accessed; own-class rule derived from teacher's own class record.
6. **Idempotent mutations** — all POST endpoints tolerate replayed offline-sync
   requests (client-generated ids like msg_<ts>_<userId>); no double effects, no
   double charges.
7. **Grade normalization** — canonical comparison so Form 3 ≡ FORM_THREE ≡ Grade 08.
8. **Server-computed analytics** — percentiles, integrity scores/flags, benchmark
   averages, impact insights, risk tiers, rankings are computed server-side and
   treated read-only by the client.
9. **Lifecycle + domain guards** — exam lifecycle states, conference no-double-booking
   and valid ranges, mark-by-exception attendance (absent = not in payload),
   coordinator cannot self-approve edit requests, contest expiryRate server-supplied.
10. **Server-issued identifiers/authority** — CTC codes issued server-side only;
    school/grade/class derived from CTC; TeacherSettings/SystemSettings authoritative.
11. **Session policy** — max 3 student sessions per device; refresh-token rotation;
    logout clears only current session.
12. **Messaging fan-out** — CLASS / CLASS_PARENTS audiences are fanned out server-side
    (one client request); unified inbox (no separate parent accounts, flag
    intendedForParent = true).
13. **Anti-abuse & resilience** — rate limiting, input validation, attachment URL
    scanning, standard error envelope, ETag/If-Match, sane retry semantics.
14. **Observability** — every agentic generation job logs agent trace, LLM provider,
    token count, moderation outcome (see Phase 7).

---

## 5. Phased Delivery Plan

Phases come from BrainBox/docs/backend_contracts/11_... Appendix A (checklist), with
agentic pipeline added as Phase 7. Each phase becomes its own session goal when it
starts; this document is the durable long-term plan. Progress = checked items.

### Phase 1 — Core Infrastructure  (goal 1) — COMPLETE (commit e1a1dae + this round)
- [x] Project setup: com.afrithecus.brainbox.api, profiles (dev/staging/prod), env-driven secrets,
      compose.yaml postgres:17, JDK21 toolchain, H2-PG test profile, Boot 4 flyway starter
- [x] Flyway V1-V5: core identity, teacher_codes, admin identity, school is_active, idempotency
- [x] Error envelope (doc 11 §8.1) + status mapping, security 401/403/429 writers, tested end to end
- [x] JWT: 24h HMAC access + claims, bearer filter, stateless chain, BCrypt, injectable clock
- [x] Auth endpoints: signup/login/me/logout/refresh rotation w/ family reuse detection (doc 01 §1)
- [x] Sessions: <=3 student / 1 teacher-parent, oldest evicted, device dedupe (doc 01 §5.1) +
      parent->child /auth/switch-session (§5.3)
- [x] RBAC: role + sub-role authorities; @PreAuthorize ADMIN guards; method security
      (spring-boot-starter-aspectj); 403 envelope for method denials
- [x] Identity admin: user list/get/patch/deactivate, subscription updates, reset-password,
      parent-link, approve/reject (doc 01 §2.2/§6/§8.2)
- [x] CTC: V2 table + student join validation; admin teacher creation w/ server-generated code,
      list/remove (doc 01 §7.2); school endpoints incl public search/all/detail (doc 01 §9.2)
- [x] Subscription/entitlement middleware: server-authoritative expiry + access levels
      (unverified students capped BASE; teacher/parent/admin exempt)
- [x] Grade normalization (GradeNormalizer) + tests
- [x] Cross-cutting: rate limiting (token bucket, 429 + Retry-After), security headers, idempotent
      POST via X-Idempotency-Key replay cache (V5), hourly purge
- [ ] Deferred by design (tracked in later phases): subscription/payment endpoints + M-Pesa
      (Phase 6 per doc 11 checklist); content-scope RLS + answer-key withholding ship with the
      Phase 2 content endpoints that need them; per-domain ownership scoping on feature routes;
      OpenAPI exposure once springdoc supports Boot 4 (contract tests used meanwhile)

Key docs: ARCHITECTURE §6/8 + Appendix A; 01; 11 §1/8; 12 (model conventions).

### Phase 2 — Student Features  (goal 2 = 2A Exams & Assessments; further sub-goals opened per domain)
- [ ] Auth endpoints, dashboard & analytics, profile (doc 01/blueprint)
- [x] Exam domain foundation (2A, doc 02 §2/§4): V6 schema (exams, exam_questions,
      exam_sessions, exam_submissions); admin authoring API (POST /admin/exams incl keys,
      publish/archive); scope filtering GLOBAL/SCHOOL start; hub state + tab lists + /exams
      listings + detail with answer-key withholding (verified by tests); parity-verified on PG18
- [ ] Exam session lifecycle (2A next): start/sync/submit, server-side auto-grading (score=-1
      sentinel), results, past-paper attempt endpoints (doc 02 §3/§5)
- [ ] Contest system: register, session, submit, server-computed integrity + leaderboards (doc 05 §1)
- [ ] Learning hub: posts, readable files, reading progress/sessions, scope filtering (doc 03 §1-4)
- [ ] Recommendations (personalized + trending, server-derived) (doc 03 §5)
- [ ] Homework with submission + auto-grading (doc 02/blueprint homework)
- [ ] Messaging: fan-out, unified inbox, class groups, read receipts (doc 05 §2)
- [ ] Doubt solving forum (ask/answer/accept/vote) (doc 05 §3)
- [ ] Career guidance + school matching + goal setting (doc 06 §1/4)
- [ ] Mock interviews incl. emotion variant + analytics (doc 06 §2)
- [ ] Mastery tracking + achievements + rewards store/redemption (doc 03 §6-8)
- [ ] Live classes (student): list/detail/register/attendance/polls (doc 05 §4)
- [ ] CBC projects (student): list/submit/history (doc 06 §3)
- [ ] News + notifications + deep links (doc 05 §5-6)
- [ ] Study tools + study sessions/insights (doc 03 §9)
- [ ] Subscription & payments: M-Pesa STK push, idempotent callbacks, entitlements (doc 07, 11 §5)

### Phase 3 — Teacher Features  (goal 3)
- [ ] Teacher auth/dashboard; class mgmt; roster (doc 04 §1-2)
- [ ] Content mgmt: materials, posts/documents, drafts, content analytics (doc 04 §2)
- [ ] Homework mgmt + grading + return/feedback + reminders (doc 04 §3)
- [ ] Attendance: mark-by-exception, summaries, analytics (doc 04 §4)
- [ ] Gradebook entries/assessments (doc 04 §5)
- [ ] Announcements CRUD + fan-out delivery + analytics (doc 04 §6)
- [ ] Feedback: templates, history, bulk (doc 04 §7)
- [ ] Live class mgmt: CRUD, lifecycle, recordings, analytics, participants, polls (doc 04 §8)
- [ ] Timetable, room bookings, house groups/peer circles, schedule changes (doc 04 §9)
- [ ] Teacher exams: CRUD, review queue, key questions, remediation (doc 02 §9)
- [ ] Learning contracts: contracts, commitments, reminders, templates (doc 04 §11)
- [ ] Class chat: class groups REST + WebSocket (doc 04 §12)
- [ ] CBC analytics: class report, student report card, strand mastery, ratings, curriculum map (doc 04 §13)
- [ ] Student analytics (doc 04 §14)
- [ ] Conferences: slots + bookings with constraints (doc 04 §15)
- [ ] Reports: async generation, branding, authorization, history (doc 04 §16)
- [ ] Teacher settings & profile (doc 04 §17)

### Phase 4 — Traditional Exam Engine  (goal 4; docs 02 §6, 10)
- [ ] Exam lifecycle: PENDING → IN_PROGRESS → CONFIRMED → PRE_FINAL → FINALIZED → PUBLISHED guards
- [ ] Subject config/components; mark entry; confirmation; finalization; publication
- [ ] Edit requests: batch per-student processing; coordinator self-approval rejection
- [ ] Analytics & rankings (server-computed), trends, grade distributions
- [ ] Grading config; coordinator panels & subject config; teacher analytics

### Phase 5 — Admin & School Management  (goal 5; doc 08)
- [ ] Admin auth + dashboards
- [ ] User management incl. approvals and role mgmt
- [ ] School config: SchoolConfig, GradeConfig, ClassGroup, attendance overview
- [ ] Content moderation + announcements + news management
- [ ] System settings (authoritative), audit logs, analytics & reports, leaderboard mgmt

### Phase 6 — Integrations & Polish  (goal 6; doc 11)
- [ ] WebRTC signaling server + TURN/STUN config (teacher live classes, student live)
- [ ] M-Pesa production integration (idempotent STK push + callbacks, state transitions)
- [ ] FCM push notifications + deep links
- [ ] Media/file upload (presigned S3/MinIO) + file security (scan URLs, size/type policy)
- [ ] Redis: JWT revocation, rate-limit counters, live-class counters
- [ ] Background job processing (offline mutation replay, sync workers, notifications, analytics)
- [ ] Monitoring & logging (actuator, structured logs, health, metrics)
- [ ] Conflict resolution (ETag/If-Match), retry strategy, resilience hardening

### Phase 7 — Agentic Content Generation Pipeline  (goal 7; ARCHITECTURE §13)
- [ ] Content cache table (JSONB) + cache-first Router; idempotent generation keyed by topic/grade/scope
- [ ] Brainbox Supervisor Agent + domain sub-agents (math, sciences, social sciences)
- [ ] Agent tools: DB metric queries, internet search, content validators
- [ ] LLM provider routing by cost/latency; per-generation token tracking (DeepSeek + others)
- [ ] Moderator gate (AI + human-in-the-loop UI) — nothing ships unmoderated
- [ ] Content JSON schema v1; answer-key stripping before student delivery; scope inheritance
- [ ] Observable jobs: agent trace, provider, tokens, moderation outcome (audit trail)
- [ ] Serve through existing client endpoints: GET /learning/post/{id}/content,
      GET /past-papers/{examId}/content, GET /materials/readable/{id}
- [ ] Budget/storage story: generate-on-demand + cache instead of PDF storage

---

## 6. Engineering Standards (apply to all phases)

- Real-world production code: no dead code, no stubs, no hardcoded network/repository
  mocks left behind; fix weak spots found in passing.
- MIT-friendly libraries only (permissive licenses that do not force open-sourcing).
- Industry-standard tests per milestone (unit + integration + security/contract);
  keep the context-load test green as the baseline.
- Idempotency, security, and scoping first — see section 4.
- API JSON matches the Android models/contracts exactly (field names, enums, casing).
- Profile base URLs mirror Android flavors (dev/staging/prod api.brainbox.com).

## 7. Tracking

- This file is the long-term plan; each phase is opened as its own session goal when
  work begins, and closed when its checklist is done and tests pass.
- Milestone order follows dependency: entitlements/scope/security come before
  feature surfaces; payments gate subscription content; Redis/S3/WebSocket/async/
  observability are integration concerns for the later phases.