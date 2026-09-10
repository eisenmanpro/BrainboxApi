package com.afrithecus.brainbox.api.career.model

/** CBC grade bands and pathways. Names must match the Android enums exactly. */

enum class CbcGradeBand(val gradeLabel: String, val gradeRange: String) {
    LOWER_PRIMARY("Lower Primary", "Grade 1-3"),
    UPPER_PRIMARY("Upper Primary", "Grade 4-6"),
    JUNIOR_SCHOOL("Junior School", "Grade 7-9"),
    SENIOR_SCHOOL("Senior School", "Grade 10-12"),
    POST_SECONDARY("Post-Secondary", "University / TVET");

    companion object {
        fun fromGrade(grade: Int?): CbcGradeBand = when (grade) {
            null -> JUNIOR_SCHOOL
            in 1..3 -> LOWER_PRIMARY
            in 4..6 -> UPPER_PRIMARY
            in 7..9 -> JUNIOR_SCHOOL
            in 10..12 -> SENIOR_SCHOOL
            else -> POST_SECONDARY
        }
    }
}

enum class CbcPathway { STEM, ARTS_AND_SPORTS_SCIENCE, SOCIAL_SCIENCES, TECHNICAL_VOCATIONAL }

enum class CbcSubject(val displayName: String) {
    MATHEMATICS("Mathematics"),
    ENGLISH("English"),
    KISWAHILI("Kiswahili"),
    SCIENCE_AND_TECHNOLOGY("Science & Technology"),
    SOCIAL_STUDIES("Social Studies"),
    CREATIVE_ARTS("Creative Arts & Craft"),
    PHYSICAL_EDUCATION("Physical & Health Education"),
    RELIGIOUS_EDUCATION("Religious Education"),
    AGRICULTURE("Agriculture"),
    BUSINESS_STUDIES("Business Studies"),
    COMPUTER_SCIENCE("Computer Science"),
    HOME_SCIENCE("Home Science"),
    HEALTH_EDUCATION("Health Education"),
    PRE_TECHNICAL_STUDIES("Pre-Technical Studies"),
    PHYSICS("Physics"),
    CHEMISTRY("Chemistry"),
    BIOLOGY("Biology"),
    HISTORY("History"),
    GEOGRAPHY("Geography"),
    GENERAL("General");

    companion object {
        fun fromLabel(label: String?): CbcSubject? {
            if (label.isNullOrBlank()) return null
            return entries.firstOrNull { it.displayName.equals(label.trim(), ignoreCase = true) || it.name.equals(label.trim(), ignoreCase = true) }
        }
    }
}

enum class CareerGoal(val displayName: String) {
    TEACHER("Teacher"),
    ARTIST("Artist"),
    AGRICULTURALIST("Agriculturalist"),
    ENTREPRENEUR("Entrepreneur"),
    NURSE("Nurse"),
    JOURNALIST("Journalist"),
    SOCIAL_WORKER("Social Worker"),
    DOCTOR("Doctor"),
    LAWYER("Lawyer"),
    ACCOUNTANT("Accountant"),
    ENGINEER("Engineer"),
    CLINICAL_OFFICER("Clinical Officer"),
    VETERINARIAN("Veterinarian"),
    PILOT("Pilot"),
    ARCHITECT("Architect"),
    SOFTWARE_ENGINEER("Software Engineer"),
    DATA_SCIENTIST("Data Scientist"),
    CIVIL_ENGINEER("Civil Engineer"),
    GENERAL("Explore Careers");

    companion object {
        fun fromName(raw: String?): CareerGoal? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) || it.displayName.equals(raw.trim(), ignoreCase = true) }
        }
    }
}
