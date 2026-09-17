package com.afrithecus.brainbox.api.content.ai

/**
 * Provider-agnostic generation and verification prompts. They live here, not in a
 * provider, so every OpenAI-compatible provider asks for the identical strict-JSON
 * schema and the answer-key rules can never drift between providers.
 */
internal object GenerationPrompts {

    val GENERATION_SYSTEM = """
        You are a curriculum-aligned content generator. Reply with a single
        strict JSON object and nothing else. The schema is:
        {
          "body": string,
          "steps": [ { "orderIndex": int, "title": string, "body": string, "figureSvg": string|null } ],
          "questions": [ {
            "orderIndex": int,
            "stepIndex": int|null,
            "type": "MULTIPLE_CHOICE"|"TRUE_FALSE"|"SHORT_ANSWER"|"MATCHING"|"ESSAY",
            "text": string,
            "options": [string]|null,
            "correctAnswer": string,
            "explanation": string|null,
            "points": int,
            "difficulty": int,
            "matchingPairs": { "left": "right" }|null
          } ],
          "confidence": number,
          "sourceUrls": [string],
          "license": string|null
        }
        Answer-key rules (mandatory):
        - Every question object MUST include a non-null "correctAnswer". A
          missing or null key makes the whole response invalid.
        - For MULTIPLE_CHOICE and TRUE_FALSE the "correctAnswer" MUST be copied
          exactly from one of the strings in that question's "options"; never a
          letter, a number or a paraphrase.
        - For a QUIZ or PRACTICE_PAPER task the response MUST contain at least
          10 and at most 12 questions, so that dropping one or two disputed
          answers still leaves at least 8.
        - A PRACTICE_PAPER is an original BrainBox paper covering the whole
          subject-grade band: write new questions only and never reproduce,
          quote or attribute a KNEC or KICD examination paper.
        - For a NOTES, LESSON or STUDY_GUIDE task (any non-assessment task) the
          response MUST contain between 3 and 5 nested check questions
          interleaved with the steps. Attach each check to the step it checks
          ("stepIndex") and give it a non-null "correctAnswer", so a lesson is
          not a wall of prose. A STUDY_GUIDE covers the whole subject-grade
          band, not a single topic.
        Never include markdown fences or commentary.
    """.trimIndent()

    val VERIFICATION_SYSTEM = """
        You are an independent answer-key verifier. For every question solve the
        problem yourself first, then answer; never assume a question already has a
        correct key. Reply with a single strict JSON object and nothing else. The
        schema is:
        {
          "answers": [ { "orderIndex": int, "answer": string } ]
        }
        Return one entry per question using the same orderIndex. For a
        multiple-choice question answer with the text of one of its options.
        Never include markdown fences or commentary.
    """.trimIndent()

    /** The generation system prompt with the subject-agent persona appended when present. */
    fun systemPrompt(persona: String?): String =
        if (persona.isNullOrBlank()) GENERATION_SYSTEM else GENERATION_SYSTEM + "\n\n" + persona.trim()

    /** The LLM-critic system prompt (Phase 7.5): pedagogy quality, not safety. */
    val CRITIQUE_SYSTEM = """
        You are an experienced Kenyan CBC pedagogy reviewer. Judge the teaching
        quality of the content below, not its safety. Reply with a single strict JSON
        object and nothing else. The schema is:
        {
          "score": number,
          "findings": [ { "severity": "BLOCKER"|"WARNING"|"INFO", "code": string, "message": string } ]
        }
        Score 1.0 means the content is clear, accurate and age-appropriate. Use
        BLOCKER only when the content would actively mislead a learner. Never include
        markdown fences or commentary.
    """.trimIndent()

    fun critiqueUserPrompt(request: ContentCritiqueRequest): String = buildString {
        appendLine("Task type: " + request.taskType)
        appendLine("Subject: " + request.subject)
        appendLine("Grade level: " + request.gradeLevel)
        request.title?.takeIf { it.isNotBlank() }?.let { appendLine("Title: " + it) }
        request.body?.takeIf { it.isNotBlank() }?.let { appendLine("Body: " + it) }
        if (request.steps.isNotEmpty()) {
            appendLine("Steps:")
            request.steps.forEachIndexed { index, step -> appendLine("  " + (index + 1) + ". " + step) }
        }
        if (request.questions.isNotEmpty()) {
            appendLine("Questions:")
            request.questions.forEachIndexed { index, question -> appendLine("  " + (index + 1) + ". " + question) }
        }
        appendLine("Return only the JSON object described by the system message.")
    }

    fun generationUserPrompt(request: GenerationRequest): String = buildString {
        appendLine("Generate a " + request.taskType + " task for a learner.")
        request.taskTypeLabel?.let { appendLine("Task label: " + it) }
        request.conceptCode?.let { appendLine("Concept code: " + it) }
        request.conceptName?.let { appendLine("Concept name: " + it) }
        appendLine("Subject: " + request.subject)
        appendLine("Grade level: " + request.gradeLevel)
        appendLine("Language: " + request.language)
        appendLine("Standard version: " + request.standardVersion)
        request.difficulty?.let { appendLine("Difficulty (1-5): " + it) }
        request.curriculumContext?.takeIf { it.isNotBlank() }?.let { appendLine("Curriculum grounding: " + it) }
        request.notes?.let { appendLine("Notes: " + it) }
        appendLine("Return only the JSON object described by the system message.")
    }

    /** No stored answer key is ever included in this prompt. */
    fun verificationUserPrompt(request: AnswerVerificationRequest): String = buildString {
        appendLine("Solve each question below independently and return your own answer.")
        appendLine("No answer is supplied and you must not assume any question was pre-answered.")
        request.questions.forEach { question ->
            append("[" + question.orderIndex + "] (" + question.type + ") " + question.text)
            val options = question.options.orEmpty()
            if (options.isNotEmpty()) append(" Options: " + options.joinToString(" | "))
            appendLine()
        }
        appendLine("Return only the JSON object described by the system message.")
    }
}
