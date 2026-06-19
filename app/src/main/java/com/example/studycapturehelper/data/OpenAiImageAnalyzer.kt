package com.example.studycapturehelper.data

import android.util.Base64
import com.example.studycapturehelper.domain.CapturedImage
import com.example.studycapturehelper.domain.ImageAnalyzer
import com.example.studycapturehelper.domain.StudyAnalysis
import javax.inject.Inject
import javax.inject.Singleton

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
        val base64 = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
        val request = ResponseRequest(
            model = "gpt-5.5",
            input = listOf(
                InputMessage(
                    role = "user",
                    content = listOf(
                        InputContent(type = "input_text", text = STUDY_PROMPT),
                        InputContent(
                            type = "input_image",
                            imageUrl = "data:${image.mimeType};base64,$base64",
                            detail = "original",
                        ),
                    ),
                ),
            ),
        )
        val text = api.createResponse("Bearer $token", request)
            .output
            .flatMap { it.content }
            .firstNotNullOfOrNull { it.text }
            ?.trim()
            .orEmpty()
        return StudyAnalysis(text.ifBlank { "No analysis result was returned." })
    }

    private companion object {
        val STUDY_PROMPT = """
You are reading a camera photo of Korean study material.

First, carefully read all visible text. Prioritize OCR accuracy over speed.
If text is small, tilted, or partially blurred, infer only when the surrounding
text strongly supports it. Do not invent hidden text.

If a multiple-choice question is visible, answer in this exact format:
[문제 번호]번 정답: X번
이유: (15 words or fewer, concise)

Rules:
- If the question number is visible, include it exactly.
- If the answer is uncertain, write "추정 70%: X번" instead of pretending certainty.
- If no question is visible, summarize the visible study material in 1-2 Korean sentences.
- Ignore irrelevant background objects.
- Do not mention personal information.
""".trimIndent()
    }
}
