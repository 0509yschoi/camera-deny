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
        val token = requireNotNull(tokenProvider.token()) {
            "A short-lived API token must be supplied by a trusted backend."
        }
        val content = buildList {
            add(InputContent(type = "input_text", text = STUDY_PROMPT))
            add(image.toInputContent())
            createDocumentCrop(image.bytes)?.let { crop ->
                add(
                    CapturedImage(bytes = crop, mimeType = "image/jpeg")
                        .toInputContent(),
                )
            }
            createTextRegionCrop(image.bytes)?.let { crop ->
                add(
                    CapturedImage(bytes = crop, mimeType = "image/jpeg")
                        .toInputContent(),
                )
            }
        }
        val request = ResponseRequest(
            model = "gpt-4o",
            input = listOf(InputMessage(role = "user", content = content)),
            maxOutputTokens = 80,
        )
        val response = api.createResponse("Bearer $token", request)
        val text = response.outputText?.trim().orEmpty().ifBlank {
            response.output
                .flatMap { it.content }
                .mapNotNull { it.text?.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
        return StudyAnalysis(text.ifBlank { "No analysis result was returned." })
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
            val step = max(1, min(document.width, document.height) / 320)
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
                    if (brightness < 145 && channelSpread < 90) {
                        minX = min(minX, x)
                        minY = min(minY, y)
                        maxX = max(maxX, x)
                        maxY = max(maxY, y)
                        hits++
                    }
                }
            }

            if (hits < 80 || maxX <= minX || maxY <= minY) return null

            val margin = 48
            minX = max(0, minX - margin)
            minY = max(0, minY - margin)
            maxX = min(bitmap.width - 1, maxX + margin)
            maxY = min(bitmap.height - 1, maxY + margin)

            val width = maxX - minX + 1
            val height = maxY - minY + 1
            if (width < bitmap.width / 5 || height < bitmap.height / 5) return null

            val cropped = Bitmap.createBitmap(bitmap, minX, minY, width, height)
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

    private fun scaleForReading(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide >= 1600) return bitmap
        val scale = min(2.0f, 1600f / maxSide)
        if (scale <= 1.05f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true,
        )
    }

    private companion object {
        val STUDY_PROMPT = """
You solve visible Korean multiple-choice study questions from camera images.
The user may provide the original frame plus cropped versions of the same page.

Return ONLY question numbers and answer choice numbers.
No explanation. No copied text. No confidence text. No greetings.

Output format:
18: 3
19: 2

Rules:
- Answer every clearly visible complete question, not just one.
- Prefer questions that show both the question sentence and its choices.
- If two or more questions are visible, use one line per question.
- Use only the visible question and visible choices when selecting an answer.
- Do not fill missing choices from memory.
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
