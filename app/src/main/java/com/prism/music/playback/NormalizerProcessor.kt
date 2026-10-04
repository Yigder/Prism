package com.prism.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Volume normalization inside Prism's own audio chain, so it works on every
 * output (Bluetooth included) and offline. Each song gets a gain from its
 * loudness; quiet songs are lifted as well as loud ones lowered, and a peak
 * limiter keeps the lifted ones from clipping.
 */
@UnstableApi
class NormalizerProcessor : BaseAudioProcessor() {
    @Volatile private var targetGain = 1f
    private var gain = 1f
    private var env = 0f
    private var rate = 48_000
    private var float = false
    private var attack = 0f
    private var release = 0f

    /** Sets the song's gain in dB; it glides there over ~100 ms. */
    fun setGainDb(db: Float) {
        targetGain = 10f.pow(db / 20f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        float = enc == C.ENCODING_PCM_FLOAT
        attack = 1f - exp(-1f / (0.0008f * rate))
        release = 1f - exp(-1f / (0.15f * rate))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        if (targetGain == 1f && abs(gain - 1f) < 1e-4f && env < 0.89f) {
            gain = 1f
            out.put(inputBuffer)
            out.flip()
            return
        }
        val glide = 1f - exp(-1f / (0.1f * rate))
        val ceiling = 0.89f // -1 dBFS
        val samples = bytes / (if (float) 4 else 2)
        for (s in 0 until samples) {
            if (s % 2 == 0) gain += (targetGain - gain) * glide
            val x = (if (float) inputBuffer.float else inputBuffer.short / 32768f) * gain
            // Peak limiter: fast attack, slow release, never above the ceiling.
            val a = abs(x)
            // Instant attack: the gain is cut on the very sample that would go over.
            env = if (a > env) a else env + (a - env) * release
            var y = if (env > ceiling) x * (ceiling / env) else x
            if (y > 0.99f) y = 0.99f else if (y < -0.99f) y = -0.99f
            if (float) out.putFloat(y) else out.putShort((y * 32767f).roundToInt().toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onReset() {
        env = 0f
    }
}
