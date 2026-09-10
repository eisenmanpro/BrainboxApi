package com.afrithecus.brainbox.api.mastery.model

/** Mastery bands (doc 03 §6.5); names must match the Android MasteryLevel enum. */
enum class MasteryLevel(val minScore: Int) {
    NOVICE(0),
    DEVELOPING(25),
    PROFICIENT(50),
    ADVANCED(75),
    MASTER(90);

    companion object {
        fun fromScore(score: Double): MasteryLevel = when {
            score >= 90.0 -> MASTER
            score >= 75.0 -> ADVANCED
            score >= 50.0 -> PROFICIENT
            score >= 25.0 -> DEVELOPING
            else -> NOVICE
        }
    }
}
