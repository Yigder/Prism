package com.prism.music.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import com.prism.music.data.prefs.EqSettings
import kotlin.math.roundToInt

object EqPresets {
    val frequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val presets: Map<String, List<Float>> = linkedMapOf(
        "Flat" to List(10) { 0f },
        "Bass Boost" to listOf(6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Bass Reducer" to listOf(-6f, -5f, -4f, -2.5f, -1f, 0f, 0f, 0f, 0f, 0f),
        "Treble Boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 6f),
        "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3.5f, 2f, 0f, -1f),
        "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        "Rock" to listOf(4.5f, 3.5f, 2f, 0f, -1f, -1f, 1f, 2.5f, 3.5f, 4f),
        "Hip-Hop" to listOf(5f, 4.5f, 2f, 3f, -1f, -1f, 1f, -0.5f, 1.5f, 2.5f),
        "Electronic" to listOf(4f, 3.5f, 1f, 0f, -2f, 2f, 0.5f, 1f, 4f, 4.5f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1f, 2f, 3f),
        "Classical" to listOf(4f, 3f, 2.5f, 2f, -1f, -1f, 0f, 2f, 3f, 3.5f),
        "Acoustic" to listOf(4f, 3.5f, 2.5f, 1f, 2f, 1.5f, 3f, 3.5f, 3f, 2f),
        "Late Night" to listOf(-3f, -2f, -1f, 0f, 1f, 2f, 1f, 0f, -2f, -4f),
        "Loudness" to listOf(5f, 3f, 0f, 0f, -2f, 0f, -1f, -4f, 4f, 1f),
    )
}

data class SpatialInfo(
    val dolbyDetected: Boolean,
    val dolbyEffects: List<String>,
    val hasEffectPanel: Boolean,
    val spatializerAvailable: Boolean,
    val spatializerEnabled: Boolean,
    val spatializerLevel: String,
    val headTracking: Boolean,
    val output: String,
)

/**
 * Owns the audio effect chain attached to the player's audio session:
 * a 10-band EQ (DynamicsProcessing on API 28+, platform Equalizer otherwise),
 * bass boost, virtualizer, loudness enhancer and loudness normalization.
 * It also announces the session so system / OEM engines such as Dolby Atmos
 * can attach their own processing.
 */
class AudioEffects(private val context: Context) {
    private var sessionId = 0
    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var settings = EqSettings()
    private var normalizationDb = 0f

    val activeSummary: String
        get() = buildList {
            if (settings.enabled) add(if (dynamics != null) "EQ 10-band" else "EQ")
            if (settings.enabled && settings.bassBoost > 0) add("Bass")
            if (normalizationDb != 0f) add("Normalize %.1f dB".format(normalizationDb))
        }.joinToString(" · ").ifEmpty { "None" }

    fun attach(newSession: Int) {
        if (newSession == sessionId || newSession == 0) return
        release(broadcast = true)
        sessionId = newSession
        context.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        })
        apply(settings)
    }

    fun setNormalization(gainDb: Float) {
        normalizationDb = gainDb
        apply(settings)
    }

    fun apply(s: EqSettings) {
        settings = s
        if (sessionId == 0) return
        val preGain = normalizationDb + if (s.enabled) s.preampDb else 0f
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                val dp = dynamics ?: DynamicsProcessing(
                    0, sessionId,
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, 10, false, 0, false, 0, true,
                    ).build()
                ).also { dynamics = it }
                val eq = DynamicsProcessing.Eq(true, true, 10)
                for (i in 0 until 10) {
                    eq.setBand(i, DynamicsProcessing.EqBand(true, EqPresets.frequencies[i], if (s.enabled) s.bands.getOrElse(i) { 0f } else 0f))
                }
                dp.setPreEqAllChannelsTo(eq)
                dp.setInputGainAllChannelsTo(preGain)
                dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 1, 1f, 60f, 10f, -1f, 0f))
                dp.enabled = s.enabled || preGain != 0f
            }.onFailure { dynamics = null; applyLegacyEq(s) }
        } else applyLegacyEq(s)

        runCatching {
            val b = bass ?: BassBoost(0, sessionId).also { bass = it }
            if (b.strengthSupported) b.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
            b.enabled = s.enabled && s.bassBoost > 0
        }
        runCatching {
            val v = virtualizer ?: Virtualizer(0, sessionId).also { virtualizer = it }
            // Superseded by Prism Spatial (software); the platform virtualizer is a no-op on most new phones.
            v.enabled = false
        }
        runCatching {
            val l = loudness ?: LoudnessEnhancer(sessionId).also { loudness = it }
            l.setTargetGain(s.loudnessGainMb.coerceAtLeast(0))
            l.enabled = s.enabled && s.loudnessGainMb > 0
        }
    }

    private fun applyLegacyEq(s: EqSettings) {
        runCatching {
            val eq = equalizer ?: Equalizer(0, sessionId).also { equalizer = it }
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                val centerHz = eq.getCenterFreq(band.toShort()) / 1000f
                val nearest = EqPresets.frequencies.indices.minBy { kotlin.math.abs(EqPresets.frequencies[it] - centerHz) }
                val mb = ((s.bands.getOrElse(nearest) { 0f } + s.preampDb + normalizationDb) * 100).roundToInt()
                    .coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), mb.toShort())
            }
            eq.enabled = s.enabled || normalizationDb != 0f
        }
    }

    fun release(broadcast: Boolean = true) {
        if (sessionId != 0 && broadcast) {
            context.sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            })
        }
        listOf(dynamics, equalizer, bass, virtualizer, loudness).forEach { runCatching { it?.release() } }
        dynamics = null; equalizer = null; bass = null; virtualizer = null; loudness = null
    }

    companion object {
        fun spatialInfo(context: Context): SpatialInfo {
            val effects = runCatching { AudioEffect.queryEffects().toList() }.getOrDefault(emptyList())
            val dolby = effects.filter {
                it.implementor.contains("dolby", true) || it.name.contains("dolby", true) ||
                    it.name.contains("atmos", true) || it.name.contains("DAP", false)
            }.map { it.name }.distinct()
            val panel = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .resolveActivity(context.packageManager) != null
            val am = context.getSystemService(AudioManager::class.java)
            var available = false
            var enabled = false
            var level = "Not supported"
            var head = false
            if (Build.VERSION.SDK_INT >= 32 && am != null) {
                val sp = am.spatializer
                available = sp.isAvailable
                enabled = sp.isEnabled
                level = when (sp.immersiveAudioLevel) {
                    android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "Multichannel"
                    android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "None"
                    else -> "Other"
                }
                if (Build.VERSION.SDK_INT >= 33) head = sp.isHeadTrackerAvailable
            }
            return SpatialInfo(dolby.isNotEmpty(), dolby, panel, available, enabled, level, head, outputDevice(context))
        }

        fun outputDevice(context: Context): String {
            val am = context.getSystemService(AudioManager::class.java) ?: return "Unknown"
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val preferred = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
            val d = preferred.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } } ?: return "Unknown"
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
                else -> "Speaker"
            }
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
            return if (name != null) "$kind · $name" else kind
        }

        fun encodingName(encoding: Int): String = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
            AudioFormat.ENCODING_OPUS -> "Opus (offload)"
            AudioFormat.ENCODING_AAC_LC -> "AAC (offload)"
            else -> "Encoding $encoding"
        }
    }
}
