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
        }
        val request = ResponseRequest(
            model = "gpt-4o",
            input = listOf(InputMessage(role = "user", content = content)),
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
            minX = max(0, minX - margin)
            minY = max(0, minY - margin)
            maxX = min(bitmap.width - 1, maxX + margin)
            maxY = min(bitmap.height - 1, maxY + margin)

            val width = maxX - minX + 1
            val height = maxY - minY + 1
            if (width < bitmap.width / 3 || height < bitmap.height / 3) return null

            val cropped = Bitmap.createBitmap(bitmap, minX, minY, width, height)
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
사진 속 한국어 객관식 문제를 읽어라. 원본 사진과 문서 영역을 자른 사진이 함께 제공될 수 있다.

먼저 문제 번호, 질문, 선택지를 실제로 읽을 수 있는지 확인해라.
선택지의 핵심 문장을 충분히 읽을 수 있을 때만 정답을 골라라.
글자가 흐려서 선택지를 구분하기 어렵다면 추측하지 말고 판독 불가라고 답해라.

형식:
판독: (읽은 문제 번호와 핵심 문장 또는 "판독 불가")
정답: X번 또는 판독 불가
이유: (짧게)

규칙:
- 여러 문제가 보이면 가장 크고 선명한 문제 하나만 답변
- 문제 번호가 보이면 반드시 포함
- 보이는 글자와 행정법 지식을 함께 사용하되, 보이지 않는 선택지를 지어내지 말 것
- 확신이 낮으면 "추정 70%: X번"처럼 표시
- 개인정보는 언급하지 말 것
""".trimIndent()
    }
}
