package com.example.studycapturehelper.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.studycapturehelper.domain.CapturedImage
import com.example.studycapturehelper.domain.ImageAnalyzer
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
        val content = buildList {
            add(InputContent(type = "input_text", text = STUDY_PROMPT))
            safeImages.forEachIndexed { index, image ->
                add(InputContent(type = "input_text", text = "Frame ${index + 1} original"))
                add(image.toInputContent())
                createDocumentCrop(image.bytes)?.let { crop ->
                    add(InputContent(type = "input_text", text = "Frame ${index + 1} document crop"))
                    add(
                        CapturedImage(bytes = crop, mimeType = "image/jpeg")
                            .toInputContent(),
                    )
                }
                createTextRegionCrop(image.bytes)?.let { crop ->
                    add(InputContent(type = "input_text", text = "Frame ${index + 1} text crop"))
                    add(
                        CapturedImage(bytes = crop, mimeType = "image/jpeg")
                            .toInputContent(),
                    )
                }
                createEnhancedTextRegionCrop(image.bytes)?.let { crop ->
                    add(InputContent(type = "input_text", text = "Frame ${index + 1} enhanced text crop"))
                    add(
                        CapturedImage(bytes = crop, mimeType = "image/jpeg")
                            .toInputContent(),
                    )
                }
            }
        }
        val request = ResponseRequest(
            model = "gpt-4o",
            input = listOf(InputMessage(role = "user", content = content)),
            maxOutputTokens = 320,
        )
        val response = api.createResponse("Bearer $token", request)
        val text = response.outputText?.trim().orEmpty().ifBlank {
            response.output
                .flatMap { it.content }
                .mapNotNull { it.text?.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
        return StudyAnalysis(formatAnswerOnly(text))
    }

    private fun CapturedImage.toInputContent(): InputContent {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return InputContent(
            type = "input_image",
            imageUrl = "data:$mimeType;base64,$base64",
        )
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
            val scaled = scaleForReading(cropped, targetMaxSide = 2200, maxScale = 3.0f)
            val enhanced = enhanceForReading(scaled)
            val out = ByteArrayOutputStream()
            enhanced.compress(Bitmap.CompressFormat.JPEG, 98, out)
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
        val enhanced = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
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
                enhanced.setPixel(
                    x,
                    y,
                    (0xFF shl 24) or (value shl 16) or (value shl 8) or value,
                )
            }
        }
        return enhanced
    }

    private fun formatAnswerOnly(text: String): String {
        if (text.isBlank()) return "?: ?"

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
            .joinToString("\n") { (question, answer) -> "$question: $answer" }
            .ifBlank { "?: ?" }
    }

    private companion object {
        const val MAX_ANALYSIS_FRAMES = 5
        val STRICT_ANSWER_REGEX = Regex(
            pattern = "(?im)^\\s*(?:q(?:uestion)?\\s*)?(\\d{1,3}|\\?)\\s*" +
                "(?:[:.)\\]-]|->|=>|\\uB300|\\uBC88)?\\s*" +
                "([1-5?])\\s*(?:\\uBC88|choice)?\\b",
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
        val STUDY_PROMPT = """
You solve visible Korean multiple-choice study questions from camera images.
The user provides up to five consecutive camera frames of the same page, plus crops and high-contrast reading aids.
Use all frames together. Combine the sharpest visible text from different frames before choosing answers.

Return ONLY question numbers and answer choice numbers.
No explanation. No copied text. No confidence text. No greetings.
Use ASCII digits and a colon. Do not use Korean words between the two numbers.

Output format:
18: 3
19: 2

Rules:
- Answer every clearly visible complete question, not just one.
- Prefer questions that show both the question sentence and its choices.
- If two or more questions are visible, use one line per question.
- Read the printed question number immediately before the question stem. Do not infer it from nearby questions.
- When frames disagree, trust the frame where the relevant printed Korean text is largest and sharpest.
- If one frame shows the question stem and another frame shows the choices, combine them for the same question number.
- Use only the visible question and visible choices when selecting an answer.
- Do not fill missing choices from memory.
- Before choosing, silently read the question stem and all visible choices.
- If the original and enhanced images disagree, trust the image where the printed Korean text is sharper.
- If a question is visible but not enough text is readable to choose, output "questionNumber: ?".
- If no question number can be read, output "?: ?".
- Keep the answer under 5 lines.
        """.trimIndent()
    }

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
