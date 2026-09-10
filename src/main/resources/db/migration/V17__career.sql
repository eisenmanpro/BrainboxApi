-- ============================================================
-- BrainboxApi V17: career guidance, school matching & goals
-- Contract: docs/backend_contracts/06_...  §1/§4 + the Android CareerApi/model.
-- Static curriculum mapping (goal -> subjects, orientation pillars) lives in
-- code (CareerCurriculum); these tables hold reference catalogs and user state.
-- ============================================================

CREATE TABLE career_goals (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    goal        varchar(64) NOT NULL,
    target_date timestamp with time zone,
    milestones  text,
    status      varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','ARCHIVED')),
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_career_goals_user ON career_goals (user_id, status);

CREATE TABLE mentors (
    id                   uuid PRIMARY KEY,
    name                 varchar(128) NOT NULL,
    university           varchar(160) NOT NULL,
    course               varchar(160) NOT NULL,
    rating               double precision NOT NULL DEFAULT 4.5,
    available_slots      integer NOT NULL DEFAULT 0,
    education_band_label varchar(120) NOT NULL DEFAULT '',
    created_at           timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE mentor_requests (
    id         uuid PRIMARY KEY,
    mentor_id  uuid NOT NULL REFERENCES mentors (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status     varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_mentor_request ON mentor_requests (mentor_id, user_id);

CREATE TABLE scholarships (
    id               uuid PRIMARY KEY,
    title            varchar(200) NOT NULL,
    provider         varchar(160) NOT NULL,
    amount           varchar(120) NOT NULL,
    deadline         timestamp with time zone NOT NULL,
    external_url     varchar(512) NOT NULL DEFAULT '',
    eligibility_label varchar(200) NOT NULL DEFAULT '',
    thumbnail_url    varchar(512),
    grade_bands      text,
    created_at       timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE matching_schools (
    id             uuid PRIMARY KEY,
    name           varchar(200) NOT NULL,
    location       varchar(160) NOT NULL,
    type           varchar(32) NOT NULL,
    cluster        varchar(8) NOT NULL,
    pathways       text,
    slots          integer NOT NULL DEFAULT 0,
    match_reason   varchar(255) NOT NULL DEFAULT '',
    required_grade varchar(64),
    required_points integer,
    rating         double precision NOT NULL DEFAULT 4.0,
    website        varchar(512),
    created_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE elective_subjects (
    id          uuid PRIMARY KEY,
    name        varchar(160) NOT NULL,
    category    varchar(64) NOT NULL,
    is_core     boolean NOT NULL DEFAULT FALSE,
    description text,
    grade_bands text,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_elective_subject_name ON elective_subjects (name);

CREATE TABLE user_elective_subjects (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_id uuid NOT NULL REFERENCES elective_subjects (id) ON DELETE CASCADE,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_elective ON user_elective_subjects (user_id, subject_id);

-- ---------------------------------------------------------------- seed data

INSERT INTO mentors (id, name, university, course, rating, available_slots, education_band_label) VALUES
 ('11111111-1111-1111-1111-000000000001', 'Amina Wanjiru', 'University of Nairobi', 'Medicine & Surgery', 4.9, 4, 'University 3rd Year'),
 ('11111111-1111-1111-1111-000000000002', 'Brian Otieno', 'JKUAT', 'Software Engineering', 4.8, 6, 'University 2nd Year'),
 ('11111111-1111-1111-1111-000000000003', 'Cynthia Mwikali', 'Kenyatta University', 'Law', 4.7, 3, 'University 4th Year'),
 ('11111111-1111-1111-1111-000000000004', 'David Kimani', 'Nairobi Polytechnic', 'Electrical Engineering', 4.6, 5, 'Technical Graduate'),
 ('11111111-1111-1111-1111-000000000005', 'Esther Njeri', 'Moi University', 'Education', 4.8, 8, 'Senior Teacher');

INSERT INTO scholarships (id, title, provider, amount, deadline, external_url, eligibility_label, grade_bands) VALUES
 ('22222222-2222-2222-2222-000000000001', 'Wings to Fly', 'Equity Bank', 'Full Secondary Support', '2027-01-15 00:00:00+00', '', 'Open to Grade 6-9', '["UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('22222222-2222-2222-2222-000000000002', 'County Bursary', 'County Government', 'Partial Support', '2026-11-30 00:00:00+00', '', 'Open to all CBC learners', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('22222222-2222-2222-2222-000000000003', 'MasterCard Foundation Scholars', 'MasterCard Foundation', 'Full Tuition', '2027-03-31 00:00:00+00', '', 'University level', '["POST_SECONDARY"]'),
 ('22222222-2222-2222-2222-000000000004', 'HELB Loan', 'HELB', 'University Loan', '2027-02-28 00:00:00+00', '', 'Open to Form 4 / Grade 12', '["SENIOR_SCHOOL"]'),
 ('22222222-2222-2222-2222-000000000005', 'STEM Innovators Award', 'KICD', 'Ksh 50,000 Grant', '2026-12-15 00:00:00+00', '', 'Open to Junior & Senior School', '["JUNIOR_SCHOOL","SENIOR_SCHOOL"]');

INSERT INTO matching_schools (id, name, location, type, cluster, pathways, slots, match_reason, required_grade, required_points, rating, website) VALUES
 ('33333333-3333-3333-3333-000000000001', 'Nairobi High School', 'Nairobi', 'Triple', 'C1', '["STEM","SOCIAL_SCIENCES"]', 120, 'Strong STEM and Social Sciences alignment', 'Form 3', 380, 4.8, ''),
 ('33333333-3333-3333-3333-000000000002', 'Mombasa Academy', 'Mombasa', 'Dual', 'C2', '["STEM"]', 80, 'Excellent STEM focus', 'Form 3', 360, 4.6, ''),
 ('33333333-3333-3333-3333-000000000003', 'Kisumu Senior School', 'Kisumu', 'Special', 'C3', '["ARTS_AND_SPORTS_SCIENCE"]', 60, 'Great for Arts and Sports Science', 'Form 3', 320, 4.4, ''),
 ('33333333-3333-3333-3333-000000000004', 'Alliance Girls High School', 'Kiambu', 'Triple', 'C1', '["STEM","SOCIAL_SCIENCES"]', 100, 'National triple-pathway school', 'Form 3', 400, 4.9, ''),
 ('33333333-3333-3333-3333-000000000005', 'Starehe Boys Centre', 'Nairobi', 'Triple', 'C1', '["STEM","TECHNICAL_VOCATIONAL"]', 90, 'Technical and STEM strengths', 'Form 3', 390, 4.8, ''),
 ('33333333-3333-3333-3333-000000000006', 'Kakamega Technical Institute', 'Kakamega', 'Special', 'C4', '["TECHNICAL_VOCATIONAL"]', 150, 'Hands-on TVET pathways', 'Form 2', 250, 4.2, ''),
 ('33333333-3333-3333-3333-000000000007', 'Nakuru Girls High School', 'Nakuru', 'Dual', 'C2', '["SOCIAL_SCIENCES","ARTS_AND_SPORTS_SCIENCE"]', 70, 'Humanities and arts focus', 'Form 3', 350, 4.5, ''),
 ('33333333-3333-3333-3333-000000000008', 'Eldoret Science Academy', 'Uasin Gishu', 'Dual', 'C2', '["STEM"]', 85, 'Science-heavy curriculum', 'Form 3', 370, 4.6, '');

INSERT INTO elective_subjects (id, name, category, is_core, description, grade_bands) VALUES
 ('44444444-4444-4444-4444-000000000001', 'Mathematics', 'STEM', TRUE, 'Core numeracy and problem solving', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000002', 'English', 'Languages', TRUE, 'Language and communication', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000003', 'Kiswahili', 'Languages', TRUE, 'National language literacy', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000004', 'Science & Technology', 'STEM', FALSE, 'Integrated science', '["UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000005', 'Computer Science', 'STEM', FALSE, 'Programming and digital literacy', '["UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000006', 'Physics', 'STEM', FALSE, 'Mechanics, waves and electricity', '["SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000007', 'Chemistry', 'STEM', FALSE, 'Matter and reactions', '["SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000008', 'Biology', 'STEM', FALSE, 'Life sciences', '["SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000009', 'History', 'Arts', FALSE, 'Kenyan and world history', '["JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000010', 'Geography', 'Arts', FALSE, 'Physical and human geography', '["JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000011', 'Business Studies', 'STEM', FALSE, 'Enterprise and commerce', '["JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000012', 'Creative Arts & Craft', 'Arts', FALSE, 'Visual and performing arts', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000013', 'Agriculture', 'STEM', FALSE, 'Farming and agri-business', '["UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000014', 'Pre-Technical Studies', 'STEM', FALSE, 'Workshop and technical skills', '["JUNIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000015', 'Home Science', 'Arts', FALSE, 'Nutrition and consumer skills', '["UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000016', 'Health Education', 'STEM', FALSE, 'Health and wellbeing', '["JUNIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000017', 'Physical & Health Education', 'Arts', FALSE, 'Sport and fitness', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000018', 'Social Studies', 'Arts', FALSE, 'Citizenship and society', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000019', 'Religious Education', 'Arts', FALSE, 'Faith and values education', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000020', 'General', 'STEM', FALSE, 'Exploratory studies', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]');
