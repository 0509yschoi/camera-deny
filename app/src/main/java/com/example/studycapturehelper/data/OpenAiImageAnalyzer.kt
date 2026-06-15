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
            model = "gpt-4o",
            input = listOf(
                InputMessage(
                    role = "user",
                    content = listOf(
                        InputContent(type = "input_text", text = STUDY_PROMPT),
                        InputContent(
                            type = "input_image",
                            imageUrl = "data:${image.mimeType};base64,$base64",
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
이미지에 문제가 보이면 아래 형식으로만 답해라. 절대 장황하게 설명하지 마라.

형식:
[문제 번호]번
정답: X번
이유: (최대 15자 이내로 간단하게)

규칙:
- 문제 번호가 보이면 반드시 함께 파악해서 답변
- 확신이 없으면 "확률 70%로 X번"처럼 표현
- 문제가 없으면 학습 자료를 한 문장으로 요약
- 개인정보는 언급하지 마라
""".trimIndent()
    }
}
