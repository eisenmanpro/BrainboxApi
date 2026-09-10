package com.afrithecus.brainbox.api.cbc.model

/** CBC project lifecycle; names match the Android ProjectStatus enum. */
enum class ProjectStatus { PENDING, APPROVED, FEATURED, REMOVED }

/** Vote direction; names match the Android VoteType enum. */
enum class VoteType { UPVOTE, DOWNVOTE }
