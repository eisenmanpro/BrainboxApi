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
          "steps": [ { "orderIndex": int, "title": string, "body": string, "figure": figure|null } ],
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
            "matchingPairs": { "left": "right" }|null,
            "figure": figure|null
          } ],
          "confidence": number,
          "sourceUrls": [string],
          "license": string|null
        }
        A "figure" above is a declarative diagram the server renders; never emit
        SVG or HTML. It is optional and worth adding only when it teaches more than
        the prose. Put at most one figure per step or question. The allowed shapes,
        all with optional "title" and "caption" strings, are:
        - { "kind": "TABLE", "headers": [string], "rows": [[string]] } (max 6 columns, 20 rows)
        - { "kind": "BAR", "categories": [string], "values": [number], "xLabel": string?, "yLabel": string?, "showValues": bool } (max 12 bars)
        - { "kind": "LINE", "categories": [string], "series": [ { "name": string?, "values": [number] } ], "xLabel": string?, "yLabel": string? } (max 4 series)
        - { "kind": "PIE", "slices": [ { "label": string, "value": number } ], "donut": bool } (max 8 slices, positive values)
        - { "kind": "NUMBER_LINE", "min": number, "max": number, "step": number?, "marks": [ { "value": number, "label": string?, "open": bool } ], "intervals": [ { "from": number, "to": number, "label": string? } ] }
        - { "kind": "FLOW", "steps": [string], "cyclic": bool } (max 8 short steps)
        - { "kind": "TREE", "nodes": [ { "id": string, "label": string, "parent": string|null } ] } (exactly one root; max 18 nodes, 6 levels)
        - { "kind": "VENN", "sets": [ { "label": string, "items": [string] } ], "shared": [string] } (two or three sets; max 4 items per region)
        - { "kind": "GEOMETRY", "viewBox": { "minX": number, "minY": number, "width": number, "height": number }, "grid": bool, "elements": [ primitive ] }
          The GEOMETRY primitives are the only figures you may compose; each has an
          optional "label". Nothing else is accepted:
          { "type": "SEGMENT", "from": [x,y], "to": [x,y], "style": "SOLID"|"DASHED" }
          { "type": "POLYGON", "points": [[x,y],...], "filled": bool }
          { "type": "CIRCLE", "center": [x,y], "radius": number }
          { "type": "ARC", "center": [x,y], "radius": number, "startAngle": degrees, "endAngle": degrees }
          { "type": "POINT", "at": [x,y] }
          { "type": "ANGLE", "vertex": [x,y], "from": [x,y], "to": [x,y] }
          { "type": "RIGHT_ANGLE", "vertex": [x,y], "from": [x,y], "to": [x,y] }
          { "type": "RECT", "from": [x,y], "to": [x,y], "filled": bool }
          { "type": "ELLIPSE", "center": [x,y], "radius": number, "radiusY": number }
          { "type": "ARROW", "from": [x,y], "to": [x,y], "style": "SOLID"|"DASHED" }
          { "type": "BEZIER", "from": [x,y], "control1": [x,y], "control2": [x,y], "to": [x,y] }
          { "type": "LABEL", "at": [x,y], "text": string }
          Geometry coordinates are in the viewBox's own units, with y increasing
          upwards, and the figure is scaled to fit; keep every coordinate inside
          the viewBox and keep a figure to at most 40 primitives.
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
