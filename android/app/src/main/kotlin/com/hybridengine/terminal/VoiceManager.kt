package com.hybridengine.terminal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceManager(private val context: Context) : RecognitionListener, TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    var isListening: Boolean = false
        private set

    var onSpeechResult: ((String) -> Unit)? = null
    var onSpeechPartial: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null

    init {
        initializeSpeechRecognizer()
        textToSpeech = TextToSpeech(context, this)
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceManager)
            }
        } else {
            Log.w("VoiceManager", "Speech recognition is not available on this device.")
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            initializeSpeechRecognizer()
        }

        val recognizer = speechRecognizer ?: run {
            onSpeechError?.invoke("Speech recognition unavailable.")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }

        try {
            recognizer.startListening(intent)
            isListening = true
            onListeningStateChanged?.invoke(true)
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error starting speech recognition: ${e.message}")
            isListening = false
            onListeningStateChanged?.invoke(false)
            onSpeechError?.invoke("Failed to start listening: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w("VoiceManager", "Error stopping speech recognition: ${e.message}")
        } finally {
            isListening = false
            onListeningStateChanged?.invoke(false)
        }
    }

    fun toggleListening() {
        if (isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun speak(text: String) {
        if (isTtsReady && text.isNotBlank()) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voidterm_voice_out")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.getDefault()
            isTtsReady = true
        } else {
            Log.w("VoiceManager", "TextToSpeech initialization failed with status $status")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        isListening = false
        onListeningStateChanged?.invoke(false)
    }

    override fun onError(error: Int) {
        isListening = false
        onListeningStateChanged?.invoke(false)
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition engine busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
            else -> "Recognition error ($error)"
        }
        onSpeechError?.invoke(errorMsg)
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        onListeningStateChanged?.invoke(false)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val spokenText = matches[0]
            onSpeechResult?.invoke(spokenText)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val partialText = matches[0]
            onSpeechPartial?.invoke(partialText)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (_: Exception) {}
    }
}
