// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates radar alert tones.
 *   play(1..3) -> 1/2/3 sharp 3200 Hz beeps separated by short gaps.
 *   playClear() -> softer two-tone descent (1100 -> 700 Hz) for "all clear".
 *
 * Volume is user-controlled via [setVolumePct] (0..100, default 50). Values
 * map through a perceptual curve so sliding below ~50 actually reduces
 * loudness noticeably.
 */
class AlertBeeper {

    private val sampleRate = 44100
    private val beepFreqHz = 3200f
    private val toneDurMs  = 80
    private val gapMs      = 110

    private val tracks = Array(3) { i -> buildBeepTrack(i + 1) }
    private val clearTrack = buildClearTrack()

    private var volumePct = DEFAULT_VOLUME_PCT
    init { applyVolume() }

    fun play(beeps: Int) {
        val track = tracks.getOrNull(beeps - 1) ?: return
        playOnce(track)
    }

    fun playClear() {
        playOnce(clearTrack)
    }

    fun setVolumePct(pct: Int) {
        volumePct = pct.coerceIn(0, 100)
        applyVolume()
    }

    fun release() {
        tracks.forEach { it.release() }
        clearTrack.release()
    }

    private fun playOnce(track: AudioTrack) {
        try { track.stop() } catch (_: IllegalStateException) {}
        try { track.setPlaybackHeadPosition(0); track.play() } catch (_: IllegalStateException) {}
    }

    private fun applyVolume() {
        val linear = volumePct / 100f
        val g = linear * linear
        tracks.forEach { it.setVolume(g) }
        clearTrack.setVolume(g)
    }

    private fun buildBeepTrack(count: Int): AudioTrack {
        val toneSamples = sampleRate * toneDurMs / 1000
        val gapSamples  = sampleRate * gapMs  / 1000
        val tone        = generateTone(toneSamples, beepFreqHz)
        val gap         = ShortArray(gapSamples)

        val buf = ShortArray(count * toneSamples + (count - 1) * gapSamples)
        var pos = 0
        repeat(count) { i ->
            tone.copyInto(buf, pos); pos += toneSamples
            if (i < count - 1) { gap.copyInto(buf, pos); pos += gapSamples }
        }
        return makeTrack(buf)
    }

    private fun buildClearTrack(): AudioTrack {
        val toneSamples = sampleRate * 110 / 1000
        val gapSamples = sampleRate * 60 / 1000
        val hi = generateTone(toneSamples, 1100f)
        val lo = generateTone(toneSamples, 700f)
        val gap = ShortArray(gapSamples)
        val buf = ShortArray(hi.size + gap.size + lo.size)
        var pos = 0
        hi.copyInto(buf, pos); pos += hi.size
        gap.copyInto(buf, pos); pos += gap.size
        lo.copyInto(buf, pos)
        return makeTrack(buf)
    }

    private fun generateTone(numSamples: Int, freqHz: Float): ShortArray {
        val buf    = ShortArray(numSamples)
        val twoPiF = 2.0 * PI * freqHz / sampleRate
        val fade   = (numSamples * 0.08).toInt().coerceAtLeast(1)
        for (i in buf.indices) {
            val env = min(
                min(i.toDouble() / fade, (numSamples - 1 - i).toDouble() / fade),
                1.0
            )
            buf[i] = (Short.MAX_VALUE * 0.75 * env * sin(twoPiF * i)).toInt().toShort()
        }
        return buf
    }

    private fun makeTrack(buf: ShortArray): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(buf, 0, buf.size) }
    }

    companion object {
        const val DEFAULT_VOLUME_PCT = 50
    }
}
