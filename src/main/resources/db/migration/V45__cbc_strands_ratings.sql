-- ============================================================
-- BrainboxApi V45: CBC strands and teacher ratings
-- doc 04 CBC analytics: the curriculum map is the server catalogue and the
-- teacher rating endpoint upserts one rating per (student, strand, term).
-- ============================================================

CREATE TABLE cbc_strands (
    id          uuid PRIMARY KEY,
    code        varchar(32) NOT NULL,
    name        varchar(160) NOT NULL,
    descriptor  text NOT NULL,
    grade_level varchar(32) NOT NULL DEFAULT 'ALL',
    subject     varchar(64) NOT NULL,
    sort_order  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_cbc_strand ON cbc_strands (code, grade_level);

CREATE TABLE cbc_ratings (
    id          uuid PRIMARY KEY,
    student_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    strand_code varchar(32) NOT NULL,
    term        varchar(32) NOT NULL,
    rating      varchar(16) NOT NULL
                CHECK (rating IN ('EXCEEDING','MEETING','APPROACHING','BELOW')),
    evidence    varchar(512),
    comments    text,
    rated_at    timestamp with time zone NOT NULL,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_cbc_rating ON cbc_ratings (student_id, strand_code, term);
CREATE INDEX ix_cbc_ratings_student ON cbc_ratings (student_id, term);

INSERT INTO cbc_strands (id, code, name, descriptor, grade_level, subject, sort_order) VALUES
('22222222-2222-4222-8222-222222220001', 'MAT-NUM', 'Numbers', 'Count, order, add, subtract, multiply and divide whole numbers, fractions and decimals.', 'ALL', 'Mathematics', 1),
('22222222-2222-4222-8222-222222220002', 'MAT-MEAS', 'Measurement', 'Measure length, mass, capacity, time and money, and solve measurement problems.', 'ALL', 'Mathematics', 2),
('22222222-2222-4222-8222-222222220003', 'MAT-GEO', 'Geometry', 'Identify, describe and construct 2D and 3D shapes and angles.', 'ALL', 'Mathematics', 3),
('22222222-2222-4222-8222-222222220004', 'MAT-DATA', 'Data Handling', 'Collect, represent, interpret and use data to solve problems.', 'ALL', 'Mathematics', 4),
('22222222-2222-4222-8222-222222220005', 'ENG-LISTEN', 'Listening and Speaking', 'Listen, respond and speak confidently for different purposes.', 'ALL', 'English', 5),
('22222222-2222-4222-8222-222222220006', 'ENG-READ', 'Reading', 'Read fluently and comprehend a range of texts.', 'ALL', 'English', 6),
('22222222-2222-4222-8222-222222220007', 'ENG-WRITE', 'Writing', 'Write clear, well-organised texts for different audiences and purposes.', 'ALL', 'English', 7),
('22222222-2222-4222-8222-222222220008', 'SCI-LIVING', 'Living Things', 'Observe, classify and explain living things and their environment.', 'ALL', 'Integrated Science', 8),
('22222222-2222-4222-8222-222222220009', 'SCI-MATTER', 'Matter', 'Investigate the properties, states and changes of matter.', 'ALL', 'Integrated Science', 9),
('22222222-2222-4222-8222-222222220010', 'SCI-ENERGY', 'Energy', 'Investigate energy, forces and their applications.', 'ALL', 'Integrated Science', 10),
('22222222-2222-4222-8222-222222220011', 'SCI-ENV', 'Environment', 'Conserve and manage the environment sustainably.', 'ALL', 'Integrated Science', 11),
('22222222-2222-4222-8222-222222220012', 'KIS-LUGHA', 'Lugha na Matumizi', 'Tumia Kiswahili kwa mawasiliano sahihi.', 'ALL', 'Kiswahili', 12),
('22222222-2222-4222-8222-222222220013', 'KIS-KUSOMA', 'Kusoma na Kuelewa', 'Soma na uelewe matini mbalimbali.', 'ALL', 'Kiswahili', 13),
('22222222-2222-4222-8222-222222220014', 'SST-CITIZEN', 'Citizenship', 'Understand rights, responsibilities and governance.', 'ALL', 'Social Studies', 14),
('22222222-2222-4222-8222-222222220015', 'SST-GEOG', 'Geography', 'Locate and describe physical and human features.', 'ALL', 'Social Studies', 15);
