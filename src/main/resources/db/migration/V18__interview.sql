-- ============================================================
-- BrainboxApi V18: mock interviews
-- Contract: docs/backend_contracts/06_... §2 + the Android InterviewApi/models.
-- The server owns the question bank and scores each answer (the client mock
-- shows the exact algorithm the app expects back).
-- ============================================================

CREATE TABLE interview_questions (
    id                uuid PRIMARY KEY,
    type              varchar(48) NOT NULL,
    text              text NOT NULL,
    category          varchar(64) NOT NULL DEFAULT 'General',
    difficulty        integer NOT NULL DEFAULT 1,
    order_index       integer NOT NULL DEFAULT 0,
    sample_answer     text,
    keywords          text,
    min_words         integer NOT NULL DEFAULT 20,
    max_words         integer NOT NULL DEFAULT 200,
    structure_phrases text,
    rubric_type       varchar(16) NOT NULL DEFAULT 'NONE',
    company_focus     varchar(160),
    school_focus      varchar(160),
    created_at        timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_interview_questions_type ON interview_questions (type, order_index);

CREATE TABLE interview_sessions (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         varchar(48) NOT NULL,
    mode         varchar(16) NOT NULL,
    difficulty   integer NOT NULL DEFAULT 1,
    status       varchar(16) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED','ABANDONED')),
    score        double precision,
    started_at   timestamp with time zone NOT NULL DEFAULT now(),
    completed_at timestamp with time zone,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_interview_sessions_user ON interview_sessions (user_id, started_at);

CREATE TABLE interview_session_questions (
    id          uuid PRIMARY KEY,
    session_id  uuid NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    question_id uuid NOT NULL REFERENCES interview_questions (id) ON DELETE CASCADE,
    order_index integer NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_interview_session_question ON interview_session_questions (session_id, question_id);

CREATE TABLE interview_answers (
    id                       uuid PRIMARY KEY,
    session_id               uuid NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    question_id              uuid NOT NULL REFERENCES interview_questions (id) ON DELETE CASCADE,
    transcribed_text         text NOT NULL DEFAULT '',
    word_count               integer NOT NULL DEFAULT 0,
    clarity_score            integer NOT NULL DEFAULT 0,
    filler_word_count        integer NOT NULL DEFAULT 0,
    filler_replacements      text,
    pace                     integer NOT NULL DEFAULT 0,
    keyword_match_count      integer NOT NULL DEFAULT 0,
    total_keywords           integer NOT NULL DEFAULT 0,
    structure_phrases_found  integer NOT NULL DEFAULT 0,
    feedback                 text NOT NULL DEFAULT '',
    score                    integer NOT NULL DEFAULT 0,
    rubric_json              text,
    diction_score            integer NOT NULL DEFAULT 0,
    pronunciation_score      integer NOT NULL DEFAULT 0,
    emotion                  varchar(32),
    emotion_confidence       double precision,
    created_at               timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_interview_answer ON interview_answers (session_id, question_id);

-- ---------------------------------------------------------------- question bank

INSERT INTO interview_questions (id, type, text, category, difficulty, order_index, sample_answer, keywords, min_words, max_words, structure_phrases, rubric_type) VALUES
 ('55555555-6666-7777-8888-000000000001', 'UNIVERSITY_INTERVIEW', 'Tell me about yourself.', 'Introduction', 1, 1, 'I am a dedicated student passionate about computer science...', '["passionate","dedicated","goals","background","experience"]', 30, 150, '["firstly","secondly","finally","in conclusion","for example"]', 'STAR'),
 ('55555555-6666-7777-8888-000000000002', 'UNIVERSITY_INTERVIEW', 'Why do you want to study at this university?', 'Motivation', 1, 2, NULL, '["research","facilities","program","reputation","alumni"]', 25, 120, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000003', 'UNIVERSITY_INTERVIEW', 'What are your strengths and weaknesses?', 'Self-awareness', 1, 3, NULL, '["strength","weakness","improve","learn","adapt"]', 40, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000004', 'UNIVERSITY_INTERVIEW', 'Describe a challenge you overcame.', 'Behavioural', 1, 4, NULL, '["challenge","overcame","learned","problem","solution"]', 50, 250, '["firstly","secondly","finally","in conclusion","for example"]', 'SOAR'),
 ('55555555-6666-7777-8888-000000000005', 'JOB_INTERVIEW', 'Tell me about yourself.', 'Introduction', 1, 1, NULL, '["experience","skills","achievements","role","company"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000006', 'JOB_INTERVIEW', 'Why do you want this job?', 'Motivation', 1, 2, NULL, '["mission","growth","contribute","values","team"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000007', 'JOB_INTERVIEW', 'Describe a difficult work situation.', 'Behavioural', 1, 3, NULL, '["situation","action","result","problem","solution"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'STAR'),
 ('55555555-6666-7777-8888-000000000008', 'JOB_INTERVIEW', 'What are your salary expectations?', 'Negotiation', 1, 4, NULL, '["market","value","negotiable","experience","range"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000009', 'LANGUAGE_SPEAKING', 'Introduce yourself in English.', 'Speaking', 1, 1, NULL, '["name","background","interests","hobby","study"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000010', 'LANGUAGE_SPEAKING', 'Describe your daily routine.', 'Speaking', 1, 2, NULL, '["morning","afternoon","evening","activity","time"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000011', 'LANGUAGE_SPEAKING', 'What are your hobbies?', 'Speaking', 1, 3, NULL, '["hobby","enjoy","free time","weekend","sport"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000012', 'PRESENTATION_PRACTICE', 'Introduce your presentation topic.', 'Structure', 1, 1, NULL, '["topic","purpose","audience","structure"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000013', 'PRESENTATION_PRACTICE', 'Explain the main point of your presentation.', 'Content', 1, 2, NULL, '["main","point","key","message"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000014', 'PRESENTATION_PRACTICE', 'How would you engage your audience?', 'Delivery', 1, 3, NULL, '["engage","audience","interactive","question"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE');
