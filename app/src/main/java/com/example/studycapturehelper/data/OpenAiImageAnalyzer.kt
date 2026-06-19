package com.example.studycapturehelper.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import com.example.studycapturehelper.domain.CapturedImage
import com.example.studycapturehelper.domain.ImageAnalyzer
import com.example.studycapturehelper.domain.PastQuestionBank
import com.example.studycapturehelper.domain.PastQuestionMatch
import com.example.studycapturehelper.domain.StudyAnalysis
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

fun interface ApiTokenProvider {
    suspend fun token(): String?
}

@Singleton
class OpenAiImageAnalyzer @Inject constructor(
    private val api: OpenAiApi,
    private val tokenProvider: ApiTokenProvider,
    private val pastQuestionBank: PastQuestionBank,
) : ImageAnalyzer {
    override suspend fun analyze(image: CapturedImage): StudyAnalysis {
        return analyze(listOf(image))
    }

    override suspend fun analyze(images: List<CapturedImage>): StudyAnalysis {
        val safeImages = images.take(MAX_ANALYSIS_FRAMES).ifEmpty {
            return StudyAnalysis("?: ?")
        }
        val token = requireNotNull(tokenProvider.token()) {
            "A short-lived API token must be supplied by a trusted backend."
        }
        val ocrContent = buildList {
            add(InputContent(type = "input_text", text = OCR_PROMPT))
            safeImages.lastOrNull()?.let { image ->
                add(InputContent(type = "input_text", text = "Reference original frame"))
                add(image.toInputContent())
            }
            safeImages.forEachIndexed { index, image ->
                if (index >= safeImages.size - MAX_READING_AID_FRAMES) {
                    addReadingAids(index, image)
                }
            }
        }
        val ocrStartedAt = SystemClock.elapsedRealtime()
        val ocrText = createTextResponse(
            token = token,
            content = ocrContent,
            maxOutputTokens = 1_200,
            model = OCR_MODEL,
            reasoningEffort = null,
        )
        val ocrMs = SystemClock.elapsedRealtime() - ocrStartedAt
        val dbStartedAt = SystemClock.elapsedRealtime()
        val pastQuestionMatches = pastQuestionBank.findRelevant(ocrText, PAST_QUESTION_LIMIT)
        val dbMs = SystemClock.elapsedRealtime() - dbStartedAt
        val solveContext = buildString {
            if (pastQuestionMatches.isNotEmpty()) {
                appendLine("PAST_QUESTION_REFERENCES:")
                appendLine(formatPastQuestionMatches(pastQuestionMatches))
                appendLine()
            }
            appendLine("OCR_TEXT:")
            append(ocrText)
        }
        val solveStartedAt = SystemClock.elapsedRealtime()
        val solveAttempt = createBestReasoningResponse(
            token = token,
            content = listOf(
                InputContent(type = "input_text", text = SOLVE_PROMPT),
                InputContent(type = "input_text", text = solveContext),
            ),
        )
        val solveMs = SystemClock.elapsedRealtime() - solveStartedAt
        return StudyAnalysis(
            text = formatAnswerOnly(solveAttempt.text),
            debugText = buildString {
                appendLine("TIMING:")
                appendLine("ocr=${ocrMs / 1000.0}s, db=${dbMs / 1000.0}s, solve=${solveMs / 1000.0}s")
                appendLine()
                appendLine("OCR_RESULT:")
                appendLine(ocrText.ifBlank { "(empty)" })
                appendLine()
                appendLine("SOLVE_RESULT:")
                appendLine(solveAttempt.text.ifBlank { "(empty)" })
                appendLine("SOLVE_MODEL: ${solveAttempt.model.ifBlank { "(none)" }}")
                solveAttempt.notes.forEach(::appendLine)
                appendLine()
                appendLine("PAST_QUESTION_REFERENCES:")
                append(formatPastQuestionMatches(pastQuestionMatches).ifBlank { "(empty)" })
            },
        )
    }

    private fun formatPastQuestionMatches(matches: List<PastQuestionMatch>): String =
        matches.joinToString("\n\n") { match ->
            buildString {
                appendLine("- id: ${match.id}")
                if (match.source.isNotBlank()) appendLine("  source: ${match.source}")
                if (match.subject.isNotBlank()) appendLine("  subject: ${match.subject}")
                appendLine("  score: ${match.score}")
                appendLine("  question: ${match.question}")
                if (match.choices.isNotEmpty()) {
                    appendLine("  choices:")
                    match.choices.forEachIndexed { index, choice ->
                        appendLine("    ${index + 1}. $choice")
                    }
                }
                appendLine("  answer: ${match.answer}")
                match.explanation?.takeIf(String::isNotBlank)?.let {
                    appendLine("  explanation: $it")
                }
            }.trimEnd()
        }

    private suspend fun createBestReasoningResponse(
        token: String,
        content: List<InputContent>,
    ): TextAttempt {
        val notes = mutableListOf<String>()
        for (model in REASONING_MODELS) {
            val text = runCatching {
                createTextResponse(
                    token = token,
                    content = content,
                    maxOutputTokens = REASONING_MAX_OUTPUT_TOKENS,
                    model = model,
                    reasoningEffort = "high",
                )
            }.getOrElse { error ->
                notes += "$model error: ${error.message ?: error::class.java.simpleName}"
                ""
            }
            if (text.isNotBlank()) {
                return TextAttempt(
                    text = text,
                    model = model,
                    notes = notes,
                )
            }
            notes += "$model returned empty output"
        }
        return TextAttempt(text = "", model = "", notes = notes)
    }

    private suspend fun createTextResponse(
        token: String,
        content: List<InputContent>,
        maxOutputTokens: Int,
        model: String,
        reasoningEffort: String?,
    ): String {
        val request = ResponseRequest(
            model = model,
            input = listOf(InputMessage(role = "user", content = content)),
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoningEffort?.let(::ReasoningConfig),
        )
        val response = api.createResponse("Bearer $token", request)
        return response.outputText?.trim().orEmpty().ifBlank {
            response.output
                .flatMap { it.content }
                .mapNotNull { it.text?.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
    }

    private fun CapturedImage.toInputContent(): InputContent {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return InputContent(
            type = "input_image",
            imageUrl = "data:$mimeType;base64,$base64",
            detail = "high",
        )
    }

    private fun MutableList<InputContent>.addReadingAids(index: Int, image: CapturedImage) {
        val crops = listOfNotNull(
            createEnhancedTextRegionCrop(image.bytes)?.let { "best enhanced text crop" to it },
            createViewportCrop(image.bytes, 0.45f, 0.35f, 1.0f, 1.0f)
                ?.let { "lower-right page crop" to it },
            createViewportCrop(image.bytes, 0.25f, 0.50f, 1.0f, 1.0f)
                ?.let { "bottom page crop" to it },
            createTextRegionCrop(image.bytes)?.let { "raw text crop" to it },
            createDocumentCrop(image.bytes)?.let { "document crop" to it },
        ).take(MAX_CROPS_PER_FRAME)

        crops.forEach { (label, crop) ->
            add(InputContent(type = "input_text", text = "Frame ${index + 1} $label"))
            add(CapturedImage(bytes = crop, mimeType = "image/jpeg").toInputContent())
        }
    }

    private fun createViewportCrop(
        bytes: ByteArray,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
    ): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val left = (bitmap.width * leftRatio).toInt().coerceIn(0, bitmap.width - 2)
            val top = (bitmap.height * topRatio).toInt().coerceIn(0, bitmap.height - 2)
            val right = (bitmap.width * rightRatio).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (bitmap.height * bottomRatio).toInt().coerceIn(top + 1, bitmap.height)
            val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val scaled = scaleForReading(cropped, targetMaxSide = 2000, maxScale = 3.0f)
            val enhanced = enhanceForReading(scaled)
            val out = ByteArrayOutputStream()
            enhanced.compress(Bitmap.CompressFormat.JPEG, 92, out)
            enhanced.recycle()
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun createDocumentCrop(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val bounds = findDocumentBounds(bitmap) ?: return null
            val width = bounds.width
            val height = bounds.height
            if (width < bitmap.width / 3 || height < bitmap.height / 3) return null

            val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, width, height)
            val scaled = scaleForReading(cropped)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 95, out)
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun createTextRegionCrop(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val document = findDocumentBounds(bitmap) ?: Bounds(0, 0, bitmap.width - 1, bitmap.height - 1)
            val text = findTextBounds(bitmap, document, margin = 48) ?: return null
            val width = text.width
            val height = text.height
            if (width < bitmap.width / 5 || height < bitmap.height / 5) return null

            val cropped = Bitmap.createBitmap(bitmap, text.left, text.top, width, height)
            val scaled = scaleForReading(cropped)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 96, out)
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun createEnhancedTextRegionCrop(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val document = findDocumentBounds(bitmap) ?: Bounds(0, 0, bitmap.width - 1, bitmap.height - 1)
            val text = findTextBounds(bitmap, document, margin = 64) ?: return null
            if (text.width < bitmap.width / 5 || text.height < bitmap.height / 5) return null

            val cropped = Bitmap.createBitmap(bitmap, text.left, text.top, text.width, text.height)
            val scaled = scaleForReading(cropped, targetMaxSide = 1800, maxScale = 2.5f)
            val enhanced = enhanceForReading(scaled)
            val out = ByteArrayOutputStream()
            enhanced.compress(Bitmap.CompressFormat.JPEG, 92, out)
            enhanced.recycle()
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun findDocumentBounds(bitmap: Bitmap): Bounds? {
        val step = max(1, min(bitmap.width, bitmap.height) / 240)
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        var hits = 0

        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (r + g + b) / 3
                val channelSpread = maxOf(r, g, b) - minOf(r, g, b)
                if (brightness > 130 && channelSpread < 95) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                    hits++
                }
            }
        }

        if (hits < 200 || maxX <= minX || maxY <= minY) return null

        val margin = 24
        return Bounds(
            left = max(0, minX - margin),
            top = max(0, minY - margin),
            right = min(bitmap.width - 1, maxX + margin),
            bottom = min(bitmap.height - 1, maxY + margin),
        )
    }

    private fun findTextBounds(bitmap: Bitmap, document: Bounds, margin: Int): Bounds? {
        val step = max(1, min(document.width, document.height) / 360)
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        var hits = 0

        for (y in document.top..document.bottom step step) {
            for (x in document.left..document.right step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (r + g + b) / 3
                val channelSpread = maxOf(r, g, b) - minOf(r, g, b)
                val isLikelyInk = brightness < 150 && channelSpread < 110
                val isStrongEdge = brightness < 115
                if (isLikelyInk || isStrongEdge) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                    hits++
                }
            }
        }

        if (hits < 80 || maxX <= minX || maxY <= minY) return null

        return Bounds(
            left = max(0, minX - margin),
            top = max(0, minY - margin),
            right = min(bitmap.width - 1, maxX + margin),
            bottom = min(bitmap.height - 1, maxY + margin),
        )
    }

    private fun scaleForReading(
        bitmap: Bitmap,
        targetMaxSide: Int = 1600,
        maxScale: Float = 2.0f,
    ): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide >= targetMaxSide) return bitmap
        val scale = min(maxScale, targetMaxSide.toFloat() / maxSide)
        if (scale <= 1.05f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true,
        )
    }

    private fun enhanceForReading(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = ((r * 30) + (g * 59) + (b * 11)) / 100
            val contrasted = (((luminance - 128) * 2.2f) + 128)
                .toInt()
                .coerceIn(0, 255)
            val value = when {
                contrasted < 95 -> 0
                contrasted > 215 -> 255
                else -> contrasted
            }
            pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhanced.setPixels(pixels, 0, width, 0, 0, width, height)
        return enhanced
    }

    private fun formatAnswerOnly(text: String): String {
        if (text.isBlank()) return "문제 번호 확인 불가, 정답 확인 불가"

        val answers = linkedMapOf<String, String>()
        val normalized = text
            .replace('\u2460', '1')
            .replace('\u2461', '2')
            .replace('\u2462', '3')
            .replace('\u2463', '4')
            .replace('\u2464', '5')

        STRICT_ANSWER_REGEX.findAll(normalized).forEach { match ->
            val question = match.groupValues[1]
            val answer = match.groupValues[2]
            answers.putIfAbsent(question, answer)
        }

        LOOSE_PAIR_REGEX.findAll(normalized).forEach { match ->
            val question = match.groupValues[1]
            val answer = match.groupValues[2]
            answers.putIfAbsent(question, answer)
        }

        VERBOSE_ANSWER_REGEX.findAll(normalized).forEach { match ->
            val question = match.groupValues[1]
            val answer = match.groupValues[2]
            answers.putIfAbsent(question, answer)
        }

        if (answers.isEmpty()) {
            ANSWER_ONLY_REGEX.find(normalized)?.let { match ->
                answers["?"] = match.groupValues[1]
            }
        }

        return answers.entries
            .take(5)
            .joinToString("\n") { (question, answer) ->
                when {
                    question == "?" && answer == "?" -> "문제 번호 확인 불가, 정답 확인 불가"
                    question == "?" -> "문제 번호 확인 불가, 정답 ${answer}번"
                    answer == "?" -> "${question}번 문제 정답 확인 불가"
                    else -> "${question}번 문제 정답 ${answer}번"
                }
            }
            .ifBlank { "문제 번호 확인 불가, 정답 확인 불가" }
    }

    private companion object {
        const val MAX_ANALYSIS_FRAMES = 5
        const val MAX_READING_AID_FRAMES = 3
        const val MAX_CROPS_PER_FRAME = 3
        const val PAST_QUESTION_LIMIT = 5
        const val OCR_MODEL = "gpt-4o"
        const val REASONING_MAX_OUTPUT_TOKENS = 900
        val REASONING_MODELS = listOf("gpt-5.5", "gpt-5.4-mini")
        val STRICT_ANSWER_REGEX = Regex(
            pattern = "(?im)^\\s*(?:q(?:uestion)?\\s*)?(\\d{1,3}|\\?)\\s*" +
                "(?:\\uBC88\\s*\\uBB38\\uC81C\\s*)?" +
                "(?:[:.)\\]-]|->|=>|\\uB300|\\uBC88)?\\s*" +
                "(?:\\uC815\\uB2F5\\s*)?([1-5?])\\s*(?:\\uBC88|choice)?\\b",
        )
        val LOOSE_PAIR_REGEX = Regex(
            pattern = "(?is)(?:^|[^0-9])(\\d{1,3})\\s*" +
                "(?:[:.)\\]-]|->|=>|\\uB300|\\uBC88|q|question)\\s*" +
                "(?:answer|ans|\\uB2F5|\\uC815\\uB2F5)?\\D{0,20}?([1-5?])\\s*(?:\\uBC88)?\\b",
        )
        val VERBOSE_ANSWER_REGEX = Regex(
            pattern = "(?is)(\\d{1,3})\\s*(?:\\uBC88|q|question|\\uBB38\\uC81C).{0,120}?" +
                "(?:\\uC815\\uB2F5|\\uB2F5|answer|ans)\\D{0,30}([1-5?])\\s*(?:\\uBC88)?",
        )
        val ANSWER_ONLY_REGEX = Regex(
            pattern = "(?is)(?:\\uC815\\uB2F5|\\uB2F5|answer|ans)\\D{0,30}([1-5?])\\s*(?:\\uBC88)?",
        )
        val OCR_PROMPT = """
You are an OCR engine for Korean multiple-choice exam images.
The user provides one reference frame plus up to five cropped reading aids from consecutive frames of the same page.
Use all frames together, but do OCR only. Do not solve.

Return OCR_RESULT only.

Output format:
OCR_RESULT:
18 question: ...
18 choices: 1=... / 2=... / 3=... / 4=...
19 question: ...
19 choices: 1=... / 2=... / 3=... / 4=...

Rules:
- Read every clearly visible complete question, not just one.
- Prefer questions that show both the question sentence and its choices.
- Read the printed question number immediately before the question stem. Do not infer it from nearby questions.
- When frames disagree, trust the frame where the relevant printed Korean text is largest and sharpest.
- If one frame shows the question stem and another frame shows the choices, combine them for the same question number.
- Copy only visible text.
- Do not fill missing choices from memory. Do not paraphrase unseen choices.
- If the original and enhanced images disagree, trust the image where the printed Korean text is sharper.
- If any choice text is not actually readable, put "[?]" for that choice.
- Mark uncertain characters with "[?]" instead of silently guessing.
- Never use legal knowledge, common exam patterns, or memory to complete OCR text.
        """.trimIndent()

        val SOLVE_PROMPT = """
You are a Korean civil-service exam multiple-choice answer engine.
The user provides OCR text from Korean civil-service exam questions. Subjects may include Korean language, English, Korean history, administrative law, and public administration.
The user may also provide similar past-question references with known answers.
Your goal is to choose the answer like a test taker, using OCR text first and past-question matches as strong hints.

Return ANSWERS only. Do not return explanations, debug, legal analysis, or choice-by-choice evaluation.

Output format:
ANSWERS:
18번 문제 정답 3번
19번 문제 정답 확인 불가

Rules:
- Use OCR_TEXT as the current question source. If a choice has a small OCR typo but its exam concept is clear, infer the intended printed sentence.
- Use PAST_QUESTION_REFERENCES as strong hints for repeated questions, repeated passages, standard answers, and similar wording.
- Do not copy a past answer blindly if the current question asks the opposite direction or the choices differ materially.
- Identify whether each question asks for the correct choice, incorrect choice, exception, best title, blank, order, grammar, reading-comprehension answer, or unknown.
- Korean negative ask phrases include "옳지 않은", "타당하지 않은", "잘못된", "아닌", "제외", "부적절한", and "않은".
- Solve like a Korean civil-service test taker: use repeated past-question patterns, standard subject knowledge, and the visible passage/choices.
- Pick the most likely answer unless the OCR is too incomplete to know the question or choices.
- Use "?" only when the OCR omitted an essential choice or the question direction is unreadable.
- Keep the answer text under 5 lines.
        """.trimIndent()
    }

    private data class TextAttempt(
        val text: String,
        val model: String,
        val notes: List<String>,
    )

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }
}
