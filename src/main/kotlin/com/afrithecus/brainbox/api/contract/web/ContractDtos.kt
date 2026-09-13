package com.afrithecus.brainbox.api.contract.web

/** Learning-contract payloads matching models/ParentModels.kt exactly. */

data class ContractCommitmentPayload(
    val id: String = "",
    val party: String = "STUDENT",
    val text: String = "",
    val isCompleted: Boolean = false,
    val dueDate: Long? = null,
    val notes: String? = null,
    val completionDate: Long? = null,
)

data class LearningContractPayload(
    val id: String = "",
    val childId: String = "",
    val teacherId: String = "",
    val term: String = "",
    val status: String = "ACTIVE",
    val startDate: Long = 0,
    val endDate: Long = 0,
    val lastUpdated: Long = 0,
    val commitments: List<ContractCommitmentPayload> = emptyList(),
)

data class ContractTemplatePayload(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val defaultCommitments: List<ContractCommitmentPayload> = emptyList(),
)
