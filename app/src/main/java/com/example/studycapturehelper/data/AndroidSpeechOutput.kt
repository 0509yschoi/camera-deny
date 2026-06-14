package com.example.studycapturehelper.data

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.example.studycapturehelper.domain.SpeechOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class AndroidSpeechOutput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechOutput {
    private var tts: TextToSpeech? = null

    override suspend fun speak(text: String) {
        val engine = tts ?: createEngine().also { tts = it }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "study-analysis")
    }

    override fun stop() {
        tts?.stop()
    }

    private suspend fun createEngine(): TextToSpeech =
        suspendCancellableCoroutine { continuation ->
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine.language = Locale.KOREAN
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    continuation.resume(engine)
                } else {
                    continuation.cancel(IllegalStateException("TTS initialization failed."))
                }
            }
        }
}
