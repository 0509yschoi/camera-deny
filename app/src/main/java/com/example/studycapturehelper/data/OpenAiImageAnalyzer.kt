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

    private companion object {
        val STUDY_PROMPT = """
이미지에서 보이는 객관식 문제를 읽고 가장 가능성 높은 정답을 하나 골라라.
절대 장황하게 설명하지 말고 아래 형식으로만 답해라.

형식:
[문제 번호]번
정답: X번
이유: (최대 15자 이내로 간단하게)

규칙:
- 여러 문제가 보이면 가장 크고 선명하게 보이는 문제 하나만 답변
- 문제 번호가 보이면 반드시 함께 파악해서 답변
- 선택지가 완전히 선명하지 않아도 읽히는 단서로 가장 가능성 높은 답을 고르기
- 확신이 없으면 "확률 70%로 X번"처럼 표현
- 문제를 전혀 읽을 수 없을 때만 "문제 판독 불가"라고 답변
- 개인정보는 언급하지 마라
""".trimIndent()
    }
}
