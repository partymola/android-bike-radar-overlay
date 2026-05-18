// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
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
 * is on, [play] and [playUrgent] use `setStereoVolume` to bias the cue
 * toward the threat's side. Two output paths support pan:
 *
 *   - **Headphone-class routes** (BT A2DP / BLE / wired / USB / hearing
 *     aid): channel labels travel intact end-to-end. App's L always
 *     reaches the rider's left ear regardless of phone rotation.
 *   - **Built-in phone speaker, landscape mount**: in landscape the
 *     earpiece (top of phone) and bottom-main are ~6-7 inches apart,
 *     plenty of stereo width. AOSP HAL maps app's L to a fixed physical
 *     speaker (earpiece on Pixel) - which is on the rider's left in
 *     ROTATION_90 (USB-right) but on the rider's right in ROTATION_270
 *     (USB-left). The app reads [rotationProvider] and swaps the pair
 *     when rotation is 270 so the cue still lands on the correct ear.
 *
 * Portrait orientation (ROTATION_0 / ROTATION_180) plays mono - the two
 * speakers are physically close together in portrait, no usable
 * lateralisation. Unknown routes also fall back to mono. The pan formula
 * tops out at (1.0, 0.7) so the cue is always audible in both channels.
 * Clear chime is always centred (it's not directional).
 */
class AlertBeeper(
    private val audioManager: AudioManager,
    private val rotationProvider: () -> Int = { Surface.ROTATION_90 },
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
        when (val result = resolvePan(
            lateralPos = lateralPos,
            monoGain = currentMonoGain(),
            panningEnabled = panningEnabled,
            invertLR = invertLR,
            hasHeadphoneRoute = hasHeadphoneRoute,
            // No headphone present implies the built-in speaker is the
            // active route (always present in `getDevices(GET_OUTPUTS)`
            // on any phone). The pan logic only fires for it in
            // landscape; portrait falls through to mono inside
            // resolvePan.
            builtinSpeakerActive = !hasHeadphoneRoute,
            rotation = rotationProvider(),
        )) {
            is PanResult.Stereo -> track.setStereoVolume(result.left, result.right)
            is PanResult.Mono -> track.setVolume(result.gain)
        }
    }

    /**
     * Pure-functional decision: given the current pan state, return
     * either a stereo gain pair (for `setStereoVolume`) or a mono gain
     * (for `setVolume`). Exhaustively unit-tested in
     * `AlertBeeperPanTest` across {pan on/off} x {headphone, speaker,
     * unknown} x {rotation 0/90/180/270} x {invert on/off}.
     */
    internal fun resolvePan(
        lateralPos: Float,
        monoGain: Float,
        panningEnabled: Boolean,
        invertLR: Boolean,
        hasHeadphoneRoute: Boolean,
        builtinSpeakerActive: Boolean,
        rotation: Int,
    ): PanResult {
        if (!panningEnabled) return PanResult.Mono(monoGain)

        if (hasHeadphoneRoute) {
            // Headphone-class route: physical channel mapping. App L
            // always reaches rider's left ear; no rotation handling.
            val (l, r) = computeStereoPair(lateralPos, invertLR, monoGain)
            return PanResult.Stereo(l, r)
        }

        if (builtinSpeakerActive) {
            // Built-in speaker: pan only useful in landscape. The HAL
            // maps app L to a fixed physical speaker (earpiece on
            // Pixel), which is on the rider's left in ROTATION_90 and
            // on the rider's right in ROTATION_270; swap channels for
            // 270 so the cue still reaches the correct ear.
            val rotationSwap = when (rotation) {
                Surface.ROTATION_90 -> false
                Surface.ROTATION_270 -> true
                else -> return PanResult.Mono(monoGain) // portrait
            }
            // XOR composition: user-invert + rotation-swap cancel when
            // both fire. Lets the invert toggle do its job on the
            // speaker path too (e.g. mounted phone is itself screen-
            // down so the OEM speaker mapping is mirrored).
            val effectiveInvert = rotationSwap xor invertLR
            val (l, r) = computeStereoPair(lateralPos, effectiveInvert, monoGain)
            return PanResult.Stereo(l, r)
        }

        // Unknown route (e.g. BT car bus, BLE speaker, casting target).
        // Pan would be misleading; default to mono.
        return PanResult.Mono(monoGain)
    }

    private fun computeStereoPair(
        lateralPos: Float,
        invertLR: Boolean,
        monoGain: Float,
    ): Pair<Float, Float> {
        val (panL, panR) = computePan(lateralPos)
        val (l, r) = if (invertLR) (panR to panL) else (panL to panR)
        return (l * monoGain) to (r * monoGain)
    }

    internal sealed class PanResult {
        data class Stereo(val left: Float, val right: Float) : PanResult()
        data class Mono(val gain: Float) : PanResult()
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
