package com.example.visao_pcd

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

import android.speech.tts.TextToSpeech

class VoiceCommandHelper(
    private val context: Context,
    private val onCommandReady: (String) -> Unit,
    private val onStatusChange: (Boolean) -> Unit,
    private val tts: TextToSpeech? = null
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val recognizerIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
    }

    fun startListening() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onStatusChange(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { onStatusChange(false) }
                    override fun onError(error: Int) { 
                        Log.e("VoiceCommand", "Erro reconhecimento: $error")
                        val msg = when(error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi, tente novamente."
                            SpeechRecognizer.ERROR_NETWORK -> "Erro de rede."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de microfone."
                            else -> "Erro no comando de voz."
                        }
                        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
                        onStatusChange(false) 
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let { onCommandReady(it.lowercase()) }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e("VoiceCommand", "Falha ao iniciar: ${e.message}")
            onStatusChange(false)
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
