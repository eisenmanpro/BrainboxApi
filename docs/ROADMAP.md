# BrainboxApi — Backend Roadmap & Long-Term Goals

**Repo:** /home/afrithecus/PROJECTS/BrainboxApi (Spring Boot backend for BrainBox)
**Companion repos:** BrainBox (Android client, complete), BrainboxWe- [x] Classes & roster foundation (2D, doc 04 §2.2 + homework prereqs): V11 teacher_classes +
      class_memberships; teacher create/list + roster add/remove with own-class ownership
      (403) + same-school enforcement; student /classes/my; stable class/student ids ready for
      homework/messaging/attendance
b (teacher/admin web, later phase)
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
- [x] Auth endpoints, dashboard & analytics, profile (2H, BACKEND_BLUEPRINT §2/§13, doc 02 §8):
      V16 user_settings; GET/PATCH /profile/settings/{userId} (self-only; plan/subscription/
      teacher code/role label always server-derived; email + admission uniqueness guards);
      GET /dashboard/assignments|contests|insights|quick-actions (assignments from roster +
      due-date labels, grade-matched contest cards, insight panel derived from live data);
      GET /analytics/student/{id} + /analytics/student/{id}/subject/{subjectId} + /analytics/class/{classId}
      (server-computed percentiles, topic mastery + weak areas, class means/distribution/rankings);
      relationship-based reads (self, linked parent, own-class teacher, school-scoped staff);
      tests + PG18 parity
      [profile/invoices deferred to Phase 6 payments (needs real PDF/signed URLs); CBC bands use
      documented defaults until per-school GradingConfig ships in Phase 4]
- [x] Exam domain foundation (2A, doc 02 §2/§4): V6 schema (exams, exam_questions,
      exam_sessions, exam_submissions); admin authoring API (POST /admin/exams incl keys,
      publish/archive); scope filtering GLOBAL/SCHOOL start; hub state + tab lists + /exams
      listings + detail with answer-key withholding (verified by tests); parity-verified on PG18
- [x] Exam session lifecycle (2A, doc 02 §3/§5): start/resume w/ withheld keys + countdown,
      idempotent progress sync, submit with server-side auto-grading (AutoGrader: MCQ/
      multi-select/matching/short/number/essay policy), results + submission endpoints,
      past-paper discovery (all/search) + idempotent client-scored attempts
- [x] Contest system (2B, doc 05 §1): V7/V8 schema; admin authoring (keys server-side);
      window-based upcoming/ongoing/completed + detail; registration w/ EXPLORER+ entitlement +
      capacity + window; session start/resume (keys withheld, end-window countdown) + idempotent
      sync; submit w/ server auto-grading + server-observed integrity flags; leaderboard w/
      ranks + user entry (server-computed); tests + PG18 parity
- [x] Learning hub (2C, doc 03): V9/V10; posts+quiz keys (withheld) w/ scope+grade filter;
      view dedupe; reading materials (readable list/detail/category/search, scope-filtered);
      reading progress upsert (validated, newest-wins) + append-only sessions; learning
      progress upsert (-1 pending, clamp 0-100, newest-wins) + continue-learning;
      server-derived personalized recommendations + trending; admin authoring; tests + PG18 parity
      [likes & file upload/presign remain for later phases]
- [ ] Recommendations (personalized + trending, server-derived) (doc 03 §5)
- [ ] Homework with submission + auto-grading (doc 02/blueprint homework)
- [x] Messaging core (2F, doc 05 §2): V14 messages (per-delivery rows, folder inbox/sent);
      direct send auth (students only to own-school staff); teacher INDIVIDUAL/CLASS/
      CLASS_PARENTS fan-out to owned classes (CLASS_PARENTS -> student inbox
      intendedForParent=true, unified inbox, no parent accounts); idempotent msg ids
      (X-Message-Id replay no-op); inbox/sent/outbox + read receipts; school member
      directory w/ role filter; tests + PG18 parity
      [class-group chat (doc 04 §12) = follow-on]
- [x] Doubt solving forum (2G, doc 05 §3): V15 doubt_questions/answers/votes; ask with tags,
      subject/search/sort (recent|popular|unanswered), detail increments views; answers with
      author role (TEACHER/STUDENT); accept gated to the question author -> CLOSED; up/down
      voting with single-count semantics + flip adjustments; tests + PG18 parity
- [x] Career guidance + school matching + goal setting (2I, doc 06 §1/§4): V17 career_goals +
      mentor/scholarship/school/elective reference catalogs; CareerRecommendation derived server-side
      from the student's real exam performance + the CBC curriculum mapping (ported from the client
      map): per-subject learning path w/ completion, skill gaps using real scores, orientation
      pillars, salary insights, milestones, growth plan, achievements; set-goal personalisation;
      elective subject list/save; idempotent mentor requests; school matching scored by pathway/
      rating/capacity/eligibility + school search/detail; career path steps; goal-plan CRUD;
      self-only scoping; tests + PG18 parity
      [jobMatches/resumeInsight left empty until jobs/resume catalogs exist]
- [x] Mock interviews (2I, doc 06 §2): V18 question bank (seeded) + interview_sessions/
      session_questions/answers; server-authoritative scoring (keyword coverage, length, filler
      words, structure phrases, STAR/SOAR/SHARE rubric, diction, pace) mirroring the client
      algorithm; difficulty variants; session start/submit/complete with lifecycle guards +
      idempotent completion; history + analytics (averages, improvement trend, category
      performance, weaknesses, emotion distribution); optional on-device emotion metadata
      persisted for analytics; self-only scoping; tests + PG18 parity
      [emotion inference itself stays on-device by contract; client sends metadata only]
- [x] Mastery tracking + achievements + rewards (2J, doc 03 §6-8): V19 topic_mastery (cumulative
      per user/topic; server recomputes score, band NOVICE..MASTER, subject resolved from exam-question
      topics) with overview/subject/weak-topics/update; V20 user_achievements + xp_events + badges +
      rewards catalogs; XP curve + level titles, derived current/longest streaks from real activity
      dates, national/school/weekly leaderboards (weekly from event-sourced XP), contest history with
      ranks/prizes, mastery tree, badge unlocking, reward store + one-time redemption with XP cost
      checks and generated coupons, scholarship eligibility flags from the career catalog;
      self-only scoping; tests + PG18 parity
      [challenge counters stay 0 until a challenge entity exists; doc /rewards alias paths superseded
      by the client's /achievements/rewards]
- [x] Live classes (2K, doc 05 §4): V21 live_classes + registrations + attendance + polls/votes;
      /live/now|upcoming|ongoing|completed|replays|spotlight + class detail; upcoming payload is a
      superset of the client LiveClass/UpcomingLiveClass so both client APIs read it; capacity-enforced
      idempotent registration; attendance upsert (self or host/admin) with PRESENT/LATE/ABSENT;
      host-only poll creation + single-count voting that moves on re-vote; replay view counts and a
      teacher spotlight derived from real class/registration data; admin authoring
      (/admin/live-classes create/list/status) as the server-side source until Phase 3 teacher CRUD;
      tests + PG18 parity
      [WebRTC signaling/join tokens remain Phase 6; joinUrl is server-provided]
- [x] CBC projects (2L, doc 06 §3): V22 cbc_projects + votes + comments + unique views; feed with
      gradeBand/subject/cbcStrand/school/status filters, recent|popular|featured sort, pagination and
      search; featured + my-projects; submission (PENDING) with server-derived author/school/media types;
      staff-only moderation (APPROVED/FEATURED/REMOVED, REMOVED hidden from non-owners); single-count
      voting that adjusts on switch/removal; threaded comments with replies + mentions; unique view
      tracking; tests + PG18 parity
      [teacher notification on new submission waits for the news/notifications + background-jobs work]
- [x] News + notifications + deep links (2M, doc 05 §5-6): V23 notifications + news_items;
      notification centre (list w/ unread + archived filters, create, read, read-all, archive, delete,
      delete-all, unread count) with the client AppNotification shape (type/urgency/priority/action
      route/label/metadata); news feed + detail + admin authoring (drafts excluded from the public feed);
      deep-link routes point at real screens (subscription / parent_dashboard / teacher_dashboard)
      [subscription lifecycle reminders]: server-derived SYSTEM notifications materialised on read and
      deduplicated on a stable key, then pruned when the condition clears - students/parents are told
      when they are on the free plan, which plan they hold, when it is nearing expiry (<=14d HIGH,
      <=3d URGENT) or expired (URGENT); teachers are nudged with the count/names of learners needing a
      renewal so they can remind parents/guardians; parents are notified about their linked children;
      tests + PG18 parity
      [push delivery (FCM) and a background scheduler for reminder generation are Phase 6; news
      likes/dislikes have no client endpoints so remain counter-only]
- [x] App-hardening contract alignment (2N): brought the backend in line with the Android prod-hardening
      commit (BrainBox/docs/ongoing/api_*_changes.md). V24 migration.
      * CBC public flow: /cbc/public/projects (list/featured/detail/vote/remove/comments/track/report/view),
        unauthenticated, guest identity via X-Guest-Id (validated guest_<uuid>); APPROVED/FEATURED only;
        voter identity unified as user:<uuid> / guest:<id>; /cbc/projects/mine now paginated
        (ProjectListResponse); POST /cbc/projects/media multipart upload returns {url, mediaType} with
        local-disk storage served from /media/{file} (S3/MinIO presign stays Phase 6)
      * News engagement: NewsItem.author; public GET /news, /news/{id}, /news/{id}/comments;
        authenticated POST comments (server-derived author), vote (UPVOTE/DOWNVOTE/NONE with authoritative
        tallies), report; admin POST/PUT/DELETE on /news (CreateNewsRequest); /admin/news is now read-only
      * Schools directory: public GET /schools/all + /schools/{id} reshaped to School/SchoolDetail
        (basicInfo/contact/academics/faculty/studentBody/tuition/reviews/enrollment/importantDates) with
        rating/reviews/placementRate/rank/logoUrls; authenticated POST /schools/{id}/reviews (server-derived
        author + rating aggregate), join-requests and reports (references, duplicate 409); public
        GET /landing/trending-schools
      * SecurityConfig now scopes permitAll to GET /schools/**, GET /news/**, GET /landing/**, GET /media/**
        and all /cbc/public/**, so the new write endpoints stay authenticated
      Tests + PG18 parity (135 tests)
- [x] Study tools + study sessions/insights (2O, doc 03 §9): V25 study_sessions (user/subject/topic
      window/duration/focus) with a unique key on (user, subject, topic, start) so replayed offline sync
      is idempotent; POST /study/sessions validates the window and computes duration server-side
      (rejecting >12h), GET /study/sessions/{userId} lists newest-first, GET /study/insights/{userId}
      derives totalStudyHours, averageSessionDuration, mostStudiedSubject, streakDays, weekly
      goal/progress (7 sessions) and data-driven recommendations server-side; self-only scoping;
      tests + PG18 parity
      [client still computes StudyInsight locally in core/ml/StudyPatternEngine; the server contract is
      ready to wire when the app moves study data off-device]
- [x] Homework attachments + past-paper flows (2P): V26 homework.related_paper_code /
      related_document_id (web homework contract); PAST_PAPER_REVIEW now requires relatedPaperCode
      and the link round-trips through teacher create/update and both payloads.
      * GET /past-papers/{examId}/content (doc 02 §4.2): builds the ExamContent payload with real cover
        metadata (school/student/subject/duration/year/mcp/questionCount), a STANDARD section of the
        exam's questions carrying per-question number/points/difficulty/topic, and a markingScheme with
        answers + marks + totalMarks + passingScore for offline self-grading. Scope-filtered and
        restricted to PAST_PAPER exams so a live digital exam's keys are never exposed.
      * POST /homework/attachments: multipart upload returning {url, mediaType}, served from /media/
        (shared MediaService local-disk store; S3/MinIO presign stays Phase 6). Submission attachmentUrl
        already flows through student submit and teacher submission payloads.
      tests + PG18 parity
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
- [x] Class chat REST (2Q, doc 04 §12): V27 class_groups + members + messages + polls/votes;
      teacher group CRUD (owner/class scoped, member sync is an immediate bulk replace), thread reads
      with before/limit pagination + unread tracking via teacherLastReadAt, send with attachments and
      reply threading, pin/unpin/delete moderation, member mute with duration, polls + single-count
      votes (LivePoll shape shared with live classes), multipart attachments through the shared media
      store (now accepts PDFs/audio/text), group teachers (TeacherClass shape) and gradebook
      contributions derived from graded homework; tests + PG18 parity
      [WebSocket transport /ws/teacher/class-chat/{groupId} remains Phase 6]
- [x] Contract-hardening wave 2R (app results pipeline + chat transport, docs/ongoing):
      - 2R-p1 class-chat transport alignment (V28): clientMessageId idempotency on every send,
        parent (/parent) and student (/student) transport controllers, per-user read state via
        class_group_reads (replaces teacherLastReadAt), announcement-only mode on the teacher PUT,
        mute enforcement on send, wider notifications.action_route for chat deep links
      - 2R-p2 exam-hub result + submission shape (no migration): ExamResultProjector builds the
        client ExamResult (title/percentile/0..1 topicBreakdown/gradingDetails/weakAreas/
        autoGradedScore/pendingReviewScore/status/markingType) and the key-free
        ExamSubmissionDetails (GET exams/{id}/submission, userAnswers as Map<String,String>);
        stored grading JSON and API payloads no longer carry correctAnswer/explanation
      - 2R-p3 traditional exam engine + student reports (V29): exam CRUD/list/get/generate,
        subject catalogue + components, coordinator grade subject/grading config, bulk mark
        upsert (server-recomputed percentage/gradeBand, combined-component validation),
        per-teacher confirm + confirmation-status + pre-final-checks, coordinator
        pre-final/finalize/publish with student+parent notification fan-out
        (exam_results/{examId} / student_report/{studentId}); GET traditional/exams/{examId}/results/me
        computes the client TraditionalStudentReport (own/linked-child, PUBLISHED gate, class
        position; overallGrade uses the percentage band to match the client's offline report
        fallback, while the teacher workbook/rankings keep the legacy raw G.TOTAL bands);
        analytics, grade-analysis, grade-wide/per-class rankings; edit requests +
        24h edit permissions; new NotificationService.notifyUser for server fan-out
      - 2R-p4 dashboard contract 14 alignment (no migration): GET users/{userId}/profile
        (subscription status Active/Expiring/Expired, numeric grade), GET users/{userId}/progress
        (XP/level at multiples of 500, streak, badges, trend), GET subscriptions/me;
        GET recommendations/user/{userId} (RecommendationsResponse) and GET recommendations/trending;
        dashboard/insights.teacherShoutout.sentAt added (featured contest already derives from the
        same published-contest query as dashboard/contests)
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


> Session status (2026-09-12): Phase 1 + 2A-2Q done, plus contract-hardening wave 2R in progress.
> App-hardening contract alignment (2N, V24), study tools (2O, V25), homework attachments +
> past-paper content (2P, V26) and class chat REST (2Q, V27) all shipped. 2R-p1 class-chat
> transport alignment (V28), 2R-p2 exam-hub result/submission shapes, 2R-p3 traditional exam
> engine + student reports (V29) and 2R-p4 dashboard contract 14 alignment all shipped. Test
> suite 157 (0 failures) + PG18 parity. Wave 2R is complete; payments/IntaSend relay (doc 14 §6)
> remains a separate Phase 6 item. Next backend work: class-chat WebSocket transport (Phase 6),
> then Phase 3 teacher portal; doc 13 analytics beyond the exam-engine outputs folds into the
> teacher-portal goal.

## 7. Tracking

- This file is the long-term plan; each phase is opened as its own session goal when
  work begins, and closed when its checklist is done and tests pass.
- Milestone order follows dependency: entitlements/scope/security come before
  feature surfaces; payments gate subscription content; Redis/S3/WebSocket/async/
  observability are integration concerns for the later phases.