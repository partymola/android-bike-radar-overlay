// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates radar alert tones.
 *   play(1..3) -> 1/2/3 sharp 3200 Hz beeps separated by short gaps.
 *   playClear() -> softer two-tone descent (1100 -> 700 Hz) for "all clear".
 *   playUrgent() -> rapid 4-pulse 3800 Hz pattern with tight 50 ms gaps,
 *                   intentionally distinct from play(3) so the rider
 *                   recognises the stationary-safety-override case.
 *
 * Volume is user-controlled via [setVolumePct] (0..100, default 50). Values
 * map through a perceptual curve so sliding below ~50 actually reduces
 * loudness noticeably.
 *
 * Stereo panning (experimental, default off via prefs): when [setPanning]
 * is on AND the active output route is a headphone-class device, [play]
 * and [playUrgent] use `setStereoVolume` to bias the cue toward the
 * threat's side. On phone speakers panning is suppressed because Pixel-
 * class speaker separation (mm-scale) gives the rider no usable
 * lateralisation, and AOSP framework does not auto-rotate channels with
 * display rotation (verified via Spatializer / AudioFlinger reading).
 * The pan formula tops out at (1.0, 0.7) so the cue is always audible
 * in both ears. Clear chime is always centred (it's not directional).
 */
class AlertBeeper(
    private val audioManager: AudioManager,
) {

    private val sampleRate = 44100
    private val beepFreqHz = 3200f
    private val toneDurMs  = 80
    private val gapMs      = 110

    private val tracks = Array(3) { i -> buildBeepTrack(i + 1) }
    private val clearTrack = buildClearTrack()
    private val urgentTrack = buildUrgentTrack()

    private var volumePct = DEFAULT_VOLUME_PCT
    @Volatile private var panningEnabled: Boolean = false
    @Volatile private var invertLR: Boolean = false
    @Volatile private var hasHeadphoneRoute: Boolean = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refreshRoute() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { refreshRoute() }
    }

    init {
        applyVolume()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshRoute()
    }

    fun play(beeps: Int, lateralPos: Float = 0f) {
        val track = tracks.getOrNull(beeps - 1) ?: return
        applyPan(track, lateralPos)
        playOnce(track)
    }

    fun playClear() {
        // Clear is non-directional. Always mono.
        clearTrack.setVolume(currentMonoGain())
        playOnce(clearTrack)
    }

    fun playUrgent(lateralPos: Float = 0f) {
        applyPan(urgentTrack, lateralPos)
        playOnce(urgentTrack)
    }

    fun setVolumePct(pct: Int) {
        volumePct = pct.coerceIn(0, 100)
        applyVolume()
    }

    fun setPanning(enabled: Boolean, invertLR: Boolean) {
        this.panningEnabled = enabled
        this.invertLR = invertLR
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        tracks.forEach { it.release() }
        clearTrack.release()
        urgentTrack.release()
    }

    private fun playOnce(track: AudioTrack) {
        try { track.stop() } catch (_: IllegalStateException) {}
        try { track.setPlaybackHeadPosition(0); track.play() } catch (_: IllegalStateException) {}
    }

    private fun applyVolume() {
        val g = currentMonoGain()
        tracks.forEach { it.setVolume(g) }
        clearTrack.setVolume(g)
        urgentTrack.setVolume(g)
    }

    private fun currentMonoGain(): Float {
        val linear = volumePct / 100f
        return linear * linear
    }

    /**
     * Pan formula: linear interpolation on [lateralPos] in [-1, +1].
     *  -1 -> (1.0, 0.7) full left
     *   0 -> (0.85, 0.85) centred
     *  +1 -> (0.7, 1.0) full right
     * Caps at 0.7 on the quiet side so the cue is never inaudible on
     * the opposite ear (preserves audibility if the rider's earbud-side
     * battery dies mid-ride).
     */
    internal fun computePan(lateralPos: Float): Pair<Float, Float> {
        val clamped = lateralPos.coerceIn(-1f, 1f)
        val left = 0.85f - 0.15f * clamped
        val right = 0.85f + 0.15f * clamped
        return left to right
    }

    private fun applyPan(track: AudioTrack, lateralPos: Float) {
        val g = currentMonoGain()
        if (!panningEnabled || !hasHeadphoneRoute) {
            // Speaker route, panning off, or no headphones connected.
            // Phone speakers are too close together (mm-scale) to
            // give the rider usable lateralisation; cue stays centred.
            track.setVolume(g)
            return
        }
        val (panL, panR) = computePan(lateralPos)
        val (l, r) = if (invertLR) (panR to panL) else (panL to panR)
        track.setStereoVolume(l * g, r * g)
    }

    private fun refreshRoute() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        hasHeadphoneRoute = outputs.any { it.type in HEADPHONE_TYPES }
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

    private fun buildUrgentTrack(): AudioTrack {
        // 4 beeps at 3800 Hz, 70 ms tone, 50 ms gap. Faster cadence and
        // higher pitch than play(3) so the rider recognises this as the
        // stationary-safety-override pattern, not a normal close-approach
        // beep.
        val toneSamples = sampleRate * 70 / 1000
        val gapSamples  = sampleRate * 50 / 1000
        val tone        = generateTone(toneSamples, 3800f)
        val gap         = ShortArray(gapSamples)
        val count = 4
        val buf = ShortArray(count * toneSamples + (count - 1) * gapSamples)
        var pos = 0
        repeat(count) { i ->
            tone.copyInto(buf, pos); pos += toneSamples
            if (i < count - 1) { gap.copyInto(buf, pos); pos += gapSamples }
        }
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

        /** Output device types that physically map app's L channel to the
         *  rider's left ear regardless of phone rotation. Pan is only
         *  applied when one of these is currently present in the audio
         *  output device list. */
        private val HEADPHONE_TYPES = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            // Hearing aids are stereo-labelled by HAL and the rider IS
            // the user, so directional pan is appropriate. BLE-speaker
            // type (portable BT speakers) is deliberately excluded -
            // they're at unknown distance from the rider and panning
            // would mislead.
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
