package com.meteor.memory.ui.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.meteor.memory.feature.ai.AiSpeech
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Plays one synthesized answer at a time. Raw TTS PCM is wrapped in a small
 * WAV container so MediaPlayer can stream it from the app cache.
 */
internal class SpeechPlayer(context: Context) {

    private val cacheDir = context.applicationContext.cacheDir
    private var player: MediaPlayer? = null
    private var wavFile: File? = null

    fun play(pcm: ByteArray, onDone: () -> Unit, onError: () -> Unit) {
        stop()
        val file = File(cacheDir, "meteor-tts-${UUID.randomUUID()}.wav")
        writeWav(file, pcm)
        wavFile = file
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            media.setDataSource(file.absolutePath)
            media.setOnCompletionListener {
                release()
                onDone()
            }
            media.setOnErrorListener { _, _, _ ->
                release()
                onError()
                true
            }
            media.prepare()
            media.start()
        } catch (failure: Exception) {
            release()
            throw failure
        }
    }

    fun stop() = release()

    private fun release() {
        runCatching { player?.release() }
        player = null
        wavFile?.delete()
        wavFile = null
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        val dataSize = pcm.size
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort()) // PCM
            putShort(1.toShort()) // mono
            putInt(AiSpeech.SAMPLE_RATE)
            putInt(AiSpeech.SAMPLE_RATE * 2) // byte rate
            putShort(2.toShort()) // block align
            putShort(16.toShort()) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        file.outputStream().use { output ->
            output.write(header.array())
            output.write(pcm)
        }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
