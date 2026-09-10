package com.afrithecus.brainbox.api.career.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Career payloads. Shapes match the Android models (models/CareerModels.kt) and
 * the endpoints the app actually calls; enum-valued fields carry enum names.
 */

data class CareerRecommendationPayload(
    val careerGoal: String,
    val gradeBand: String,
    val pathway: String? = null,
    val learningPath: List<CareerPathNodePayload>,
    val skillGaps: List<SkillGapPayload>,
    val scholarships: List<ScholarshipPayload>,
    val mentorMatches: List<MentorMatchPayload>,
    val roleTracks: List<CareerPathTrackPayload> = emptyList(),
    val jobMatches: List<CareerOpportunityPayload> = emptyList(),
    val resumeInsight: ResumeInsightPayload? = null,
    val salaryInsights: List<SalaryInsightPayload> = emptyList(),
    val milestones: List<CareerMilestonePayload> = emptyList(),
    val alerts: List<CareerAlertPayload> = emptyList(),
    val growthPlan: List<GrowthPlanStepPayload> = emptyList(),
    val discoverySignals: List<DiscoverySignalPayload> = emptyList(),
    val orientationAnalysis: OrientationAnalysisPayload? = null,
    val achievements: List<CareerAchievementPayload> = emptyList(),
    val interviewFocus: String = "Behavioral and role-specific",
)

data class CareerPathNodePayload(
    val id: String,
    val title: String,
    val subject: String,
    val cbcStrand: String,
    val gradeRange: String,
    val estimatedHours: Int,
    val requiredExams: List<String>,
    val isLocked: Boolean,
    val isCompleted: Boolean,
    val isCurrent: Boolean,
    val order: Int,
)

data class SkillGapPayload(val skill: String, val userScore: Int, val targetScore: Int, val importance: Int)

data class ScholarshipPayload(
    val id: String,
    val title: String,
    val provider: String,
    val amount: String,
    val deadline: Long,
    val externalUrl: String,
    val eligibilityLabel: String = "",
    val thumbnailUrl: String? = null,
)

data class MentorMatchPayload(
    val id: String,
    val mentorName: String,
    val university: String,
    val course: String,
    val rating: Double,
    val availableSlots: Int,
    val educationBandLabel: String = "",
    val isRequested: Boolean = false,
)

data class CareerPathTrackPayload(
    val id: String,
    val title: String,
    val stage: String,
    val description: String,
    val fitScore: Int,
    val highlight: String,
)

data class CareerOpportunityPayload(
    val id: String,
    val title: String,
    val company: String,
    val opportunityType: String,
    val location: String,
    val matchScore: Int,
    val compensation: String,
    val reason: String,
)

data class ResumeInsightPayload(
    val score: Int,
    val summary: String,
    val keywordSuggestions: List<String>,
    val bulletSuggestions: List<String>,
)

data class SalaryInsightPayload(
    val role: String,
    val salaryRange: String,
    val demand: String,
    val trend: String,
    val growthPercent: Int,
)

data class CareerMilestonePayload(val title: String, val detail: String, val dueLabel: String, val completed: Boolean)

data class CareerAlertPayload(val id: String, val title: String, val detail: String, val severity: String)

data class GrowthPlanStepPayload(val phase: String, val title: String, val focus: String, val duration: String)

data class DiscoverySignalPayload(val label: String, val value: String)

data class OrientationPillarPayload(val title: String, val score: Int, val subjects: List<String>, val color: Long)

data class OrientationAnalysisPayload(
    val pillars: List<OrientationPillarPayload>,
    val predictedPathway: String,
    val insight: String,
    val confidenceScore: Int,
    val maturity: String = "STABLE",
)

data class CareerAchievementPayload(val title: String, val progress: String, val unlocked: Boolean)

data class MentorRequestResultPayload(val status: String, val message: String)

data class SubjectSaveResultPayload(val status: String, val savedCount: Int)

data class ElectiveSubjectPayload(
    val id: String,
    val name: String,
    val category: String,
    val isCore: Boolean = false,
    val description: String = "",
)

data class MatchingSchoolPayload(
    val id: String,
    val name: String,
    val location: String,
    val type: String,
    val cluster: String,
    val pathways: List<String>,
    val slots: Int,
    val matchReason: String,
    val matchScore: Int = 0,
)

data class CareerPathPayload(val careerId: String, val steps: List<CareerPathStepPayload>)

data class CareerPathStepPayload(
    val stage: String,
    val description: String,
    val duration: String,
    val requirements: List<String>,
)

/** Persisted career plan (doc 06 §1.4). */
data class CareerGoalPayload(
    val id: String,
    val userId: String,
    val careerId: String,
    val targetDate: Long? = null,
    val milestones: List<String> = emptyList(),
    val status: String = "ACTIVE",
)

data class CareerGoalRequest(
    @field:NotBlank val userId: String,
    @field:NotBlank val careerId: String,
    val targetDate: Long? = null,
    val milestones: List<String> = emptyList(),
)

data class CareerGoalUpdateRequest(
    val careerId: String? = null,
    val targetDate: Long? = null,
    val milestones: List<String>? = null,
    val status: String? = null,
)

/** Body for POST /career/schools/match (doc 06 §4.3). */
data class SchoolMatchRequest(
    @field:NotBlank val userId: String,
    val careerPath: String? = null,
    val academicPerformance: Double? = null,
    @field:Size(max = 160) val preferredLocation: String? = null,
)
