package com.example.studycapturehelper.data

import android.content.Context
import com.example.studycapturehelper.domain.PastQuestionBank
import com.example.studycapturehelper.domain.PastQuestionMatch
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AssetPastQuestionBank @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) : PastQuestionBank {
    private val adapter = moshi.adapter(PastQuestionRecord::class.java)
    private val records by lazy { loadRecords() }

    override suspend fun findRelevant(ocrText: String, limit: Int): List<PastQuestionMatch> =
        withContext(Dispatchers.Default) {
            val queryTokens = tokenize(ocrText)
            if (queryTokens.isEmpty()) return@withContext emptyList()

            records
                .asSequence()
                .mapNotNull { record ->
                    val score = score(record, queryTokens)
                    if (score <= 0) return@mapNotNull null
                    PastQuestionMatch(
                        id = record.id,
                        source = record.source.orEmpty(),
                        subject = record.subject.orEmpty(),
                        question = record.question,
                        choices = record.choices,
                        answer = record.answer,
                        explanation = record.explanation,
                        score = score,
                    )
                }
                .sortedWith(compareByDescending<PastQuestionMatch> { it.score }.thenBy { it.id })
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    private fun loadRecords(): List<PastQuestionRecord> = runCatching {
        context.assets.open(QUESTION_BANK_ASSET).bufferedReader().useLines { lines ->
            lines
                .map(String::trim)
                .filter { it.isNotBlank() }
                .mapNotNull(adapter::fromJson)
                .filter { it.question.isNotBlank() && it.answer.isNotBlank() }
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun score(record: PastQuestionRecord, queryTokens: Set<String>): Int {
        val text = buildString {
            append(record.subject).append(' ')
            append(record.question).append(' ')
            append(record.choices.joinToString(" "))
            append(' ')
            append(record.keywords.joinToString(" "))
        }
        val recordTokens = tokenize(text)
        var score = queryTokens.count { it in recordTokens }

        val keywordTokens = tokenize(record.keywords.joinToString(" "))
        score += keywordTokens.count { it in queryTokens } * 3

        val subjectTokens = tokenize(record.subject.orEmpty())
        if (subjectTokens.any { it in queryTokens }) score += 2
        return score
    }

    private fun tokenize(text: String): Set<String> =
        TOKEN_REGEX.findAll(text)
            .map { it.value.lowercase() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private companion object {
        const val QUESTION_BANK_ASSET = "exam_question_bank.jsonl"
        val TOKEN_REGEX = Regex("[0-9A-Za-z가-힣]+")
        val STOP_WORDS = setOf(
            "대한",
            "관한",
            "다음",
            "설명",
            "옳은",
            "옳지",
            "않은",
            "것은",
            "있는",
            "없는",
            "경우",
        )
    }
}

data class PastQuestionRecord(
    val id: String,
    val source: String? = null,
    val subject: String? = null,
    val question: String,
    val choices: List<String> = emptyList(),
    val answer: String,
    val explanation: String? = null,
    val keywords: List<String> = emptyList(),
)
