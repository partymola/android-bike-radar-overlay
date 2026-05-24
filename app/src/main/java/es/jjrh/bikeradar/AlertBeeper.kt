// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates radar alert tones.
 *   play(1..3) -> 1/2/3 sharp 3200 Hz beeps separated by short gaps.
 *   playClear() -> softer two-tone descent (1100 -> 700 Hz) for "all clear".
 *   playUrgent() -> rapid 4-pulse 3800 Hz pattern with tight 50 ms gaps,
 *                   intentionally distinct from play(3) so the rider
 *                   recognises the stationary-safety-override case.
 *   playCriticalBattery() -> low (520 Hz) soft slow two-tone for "rear
 *                   radar battery critical". Deliberately a different
 *                   timbre-CLASS from the sharp/high threat beeps and from
 *                   the mid descending "all clear" - it is a status cue,
 *                   not a threat, and must never read as one. First cut;
 *                   tune the pitch/cadence on ride evidence.
 *
 * Volume is user-controlled via [setVolumePct] (0..100, default 50). Values
 * map through a perceptual curve so sliding below ~50 actually reduces
 * loudness noticeably.
 *
 * Stereo panning (experimental, default off via prefs): when [setPanning]
 * is on, [play] and [playUrgent] bias the cue toward the threat's side by
 * playing one of [PAN_BUCKETS] pre-built stereo tracks whose L/R balance is
 * baked into the samples (see [playPanned] / [nearestPanBucket]). When pan
 * is off / portrait / an unknown route, the cue plays a plain MONO track at
 * the same level as before - no stereo-downmix level shift on the built-in
 * speaker. Two output paths support pan:
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
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) {

    private val sampleRate = 44100
    private val beepFreqHz = 3200f
    private val toneDurMs  = 80
    private val gapMs      = 110

    // Mono cue PCM, built once. Reused to make both the mono default-path
    // track and the stereo pan-bucket tracks.
    private val beepPcm: Array<ShortArray> = Array(3) { i -> buildBeepPcm(i + 1) }
    private val urgentPcm: ShortArray = buildUrgentPcm()

    // Pan buckets: PAN_BUCKETS L/R ratios baked from the same [computePan]
    // formula resolvePan uses (peak channel normalised to 1.0). Selecting
    // the nearest bucket at play time replaces per-channel setStereoVolume
    // (deprecated since API 21, no per-channel replacement). [bucketImbalance]
    // is the peak-normalised (right-left) per bucket - the same metric
    // [nearestPanBucket] computes at runtime.
    internal val bucketScales: Array<Pair<Float, Float>> = Array(PAN_BUCKETS) { k ->
        val (l, r) = computePan(BUCKET_LATERAL_POS[k])
        val peak = maxOf(l, r)
        (l / peak) to (r / peak)
    }
    private val bucketImbalance: FloatArray =
        FloatArray(PAN_BUCKETS) { k -> bucketScales[k].second - bucketScales[k].first }

    // Default (non-panned) path stays MONO - byte-identical level to the
    // pre-bucket code, no stereo-downmix shift on the phone speaker.
    private val beepMono: Array<AudioTrack> = Array(3) { i -> makeTrack(beepPcm[i]) }
    private val urgentMono: AudioTrack = makeTrack(urgentPcm)
    // Pan path: one stereo track per cue per bucket.
    private val beepBuckets: Array<Array<AudioTrack>> = Array(3) { i ->
        Array(PAN_BUCKETS) { b -> makeStereoTrack(beepPcm[i], bucketScales[b].first, bucketScales[b].second) }
    }
    private val urgentBuckets: Array<AudioTrack> =
        Array(PAN_BUCKETS) { b -> makeStereoTrack(urgentPcm, bucketScales[b].first, bucketScales[b].second) }
    private val clearTrack = buildClearTrack()
    private val criticalBatteryTrack = buildCriticalBatteryTrack()

    // Track-duration table for the abandon-timer. Computed at build time
    // from the same sample counts the AudioTrack contents use, so the
    // timer never under-shoots the actual playback.
    private val beepDurationMs: IntArray = IntArray(3) { count ->
        (count + 1) * toneDurMs + count * gapMs
    }
    private val clearDurationMs: Int = 110 + 60 + 110
    private val urgentDurationMs: Int = 4 * 70 + 3 * 50
    private val criticalBatteryDurationMs: Int = 2 * 160 + 1 * 140

    private var volumePct = DEFAULT_VOLUME_PCT
    @Volatile private var panningEnabled: Boolean = false
    @Volatile private var invertLR: Boolean = false
    @Volatile private var hasHeadphoneRoute: Boolean = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refreshRoute() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { refreshRoute() }
    }

    // Audio-focus state. One request object reused across plays; gain
    // is GAIN_TRANSIENT_MAY_DUCK so media (podcasts / music) ducks for
    // the cue and restores after. Walk-away alarm uses the stronger
    // _EXCLUSIVE path elsewhere; close-pass beeps don't need to pre-
    // empt other audio, just be reliably heard above it.
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            // Empty listener: close-pass beeps are fire-and-forget. Loss
            // events are not actionable - the cue is already buffered to
            // the audio HAL by the time any LOSS callback would land.
            .setOnAudioFocusChangeListener { }
            .build()

    @Volatile private var hasFocus: Boolean = false
    private val abandonHandler = Handler(Looper.getMainLooper())
    private val abandonRunnable = Runnable {
        if (hasFocus) {
            try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Throwable) {}
            hasFocus = false
        }
    }

    init {
        applyVolume()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshRoute()
    }

    fun play(beeps: Int, lateralPos: Float = 0f) {
        val idx = beeps - 1
        if (idx !in beepMono.indices) return
        val durationMs = beepDurationMs.getOrNull(idx) ?: return
        executor.execute {
            if (suppressForCall()) return@execute
            playPanned(beepMono[idx], beepBuckets[idx], durationMs, lateralPos)
        }
    }

    fun playClear() {
        executor.execute {
            if (suppressForCall()) return@execute
            // Clear is non-directional. Always mono.
            clearTrack.setVolume(currentMonoGain())
            playWithFocus(clearTrack, clearDurationMs)
        }
    }

    fun playUrgent(lateralPos: Float = 0f) {
        executor.execute {
            if (suppressForCall()) return@execute
            playPanned(urgentMono, urgentBuckets, urgentDurationMs, lateralPos)
        }
    }

    /** Rear-radar critical-battery status cue. Non-directional (mono), like
     *  the clear chime - it is not about a threat's location. */
    fun playCriticalBattery() {
        executor.execute {
            if (suppressForCall()) return@execute
            criticalBatteryTrack.setVolume(currentMonoGain())
            playWithFocus(criticalBatteryTrack, criticalBatteryDurationMs)
        }
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
        abandonHandler.removeCallbacks(abandonRunnable)
        if (hasFocus) {
            try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Throwable) {}
            hasFocus = false
        }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        beepMono.forEach { it.release() }
        beepBuckets.forEach { row -> row.forEach { it.release() } }
        urgentMono.release()
        urgentBuckets.forEach { it.release() }
        clearTrack.release()
        criticalBatteryTrack.release()
        if (executor is java.util.concurrent.ExecutorService) executor.shutdown()
    }

    /**
     * MODE_IN_CALL guard. When a phone call holds audio focus
     * EXCLUSIVE, USAGE_ALARM plays can be silenced at the speaker
     * mid-call without indication. Skipping the audio path entirely
     * preserves call audio integrity; the visual overlay and (future)
     * wrist haptic still fire.
     */
    private fun suppressForCall(): Boolean =
        audioManager.mode == AudioManager.MODE_IN_CALL

    private fun playWithFocus(track: AudioTrack, durationMs: Int) {
        if (!hasFocus) {
            val granted = try {
                audioManager.requestAudioFocus(focusRequest) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (_: Throwable) { false }
            hasFocus = granted
        }
        playOnce(track)
        // Re-arm the abandon timer. Back-to-back plays extend the
        // window so media stays ducked across the burst rather than
        // restoring + re-ducking between cues.
        abandonHandler.removeCallbacks(abandonRunnable)
        abandonHandler.postDelayed(abandonRunnable, (durationMs + ABANDON_SAFETY_MARGIN_MS).toLong())
    }

    private fun playOnce(track: AudioTrack) {
        try { track.stop() } catch (_: IllegalStateException) {}
        try { track.setPlaybackHeadPosition(0); track.play() } catch (_: IllegalStateException) {}
    }

    private fun applyVolume() {
        val g = currentMonoGain()
        beepMono.forEach { it.setVolume(g) }
        beepBuckets.forEach { row -> row.forEach { it.setVolume(g) } }
        urgentMono.setVolume(g)
        urgentBuckets.forEach { it.setVolume(g) }
        clearTrack.setVolume(g)
        criticalBatteryTrack.setVolume(g)
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

    /**
     * Play the cue with stereo panning. [resolvePan] stays the decision
     * authority (route / rotation / invert / volume); a [PanResult.Mono]
     * plays the plain mono track, a [PanResult.Stereo] maps to the nearest
     * pre-built pan bucket. The absolute level is applied with the non-
     * deprecated [AudioTrack.setVolume] (uniform). Because each bucket's
     * peak channel is normalised to 1.0, playing the chosen bucket at
     * `setVolume(max(left, right))` reproduces resolvePan's gains.
     */
    private fun playPanned(
        monoTrack: AudioTrack,
        buckets: Array<AudioTrack>,
        durationMs: Int,
        lateralPos: Float,
    ) {
        val track: AudioTrack
        val level: Float
        when (val result = resolvePan(
            lateralPos = lateralPos,
            monoGain = currentMonoGain(),
            panningEnabled = panningEnabled,
            invertLR = invertLR,
            hasHeadphoneRoute = hasHeadphoneRoute,
            // No headphone present implies the built-in speaker is the
            // active route (always present in `getDevices(GET_OUTPUTS)`
            // on any phone). The pan logic only fires for it in
            // landscape; portrait falls through to mono inside resolvePan.
            builtinSpeakerActive = !hasHeadphoneRoute,
            rotation = rotationProvider(),
        )) {
            is PanResult.Mono -> { track = monoTrack; level = result.gain }
            is PanResult.Stereo -> {
                track = buckets[nearestPanBucket(result.left, result.right)]
                level = maxOf(result.left, result.right)
            }
        }
        track.setVolume(level)
        playWithFocus(track, durationMs)
    }

    /**
     * Map a resolved stereo gain pair to the nearest pan bucket by L/R
     * imbalance, normalised by the louder channel so it is volume-
     * independent. Invert and rotation are already folded into the gains
     * resolvePan returns. Ties favour the lower index (strict `<`). Pure;
     * [bucketImbalance] is derived from [computePan].
     */
    internal fun nearestPanBucket(left: Float, right: Float): Int {
        val peak = maxOf(left, right)
        if (peak <= 0f) return CENTER_BUCKET
        val imbalance = (right - left) / peak
        var best = CENTER_BUCKET
        var bestDist = Float.MAX_VALUE
        for (k in bucketImbalance.indices) {
            val d = abs(imbalance - bucketImbalance[k])
            if (d < bestDist) { bestDist = d; best = k }
        }
        return best
    }

    /**
     * Pure-functional decision: given the current pan state, return either
     * a stereo gain pair or a mono gain. The caller ([playPanned]) maps the
     * result to a mono track or a pre-built pan bucket. Exhaustively unit-
     * tested in `AlertBeeperPanTest` across {pan on/off} x {headphone,
     * speaker, unknown} x {rotation 0/90/180/270} x {invert on/off}.
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

    private fun buildBeepPcm(count: Int): ShortArray {
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
        return buf
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

    private fun buildUrgentPcm(): ShortArray {
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
        return buf
    }

    private fun buildCriticalBatteryTrack(): AudioTrack {
        // Low (520 Hz) soft, slow two-tone. Low pitch + slow cadence put it
        // in a different timbre-class from the sharp 3200/3800 Hz threat
        // beeps and from the 1100->700 Hz "all clear" descent, so the rider
        // reads it as a status cue, not a threat. Equal tones (no descent)
        // also separate it from the clear chime. First cut - tune on rides.
        val toneSamples = sampleRate * 160 / 1000
        val gapSamples  = sampleRate * 140 / 1000
        val tone        = generateTone(toneSamples, 520f)
        val gap         = ShortArray(gapSamples)
        val buf = ShortArray(2 * toneSamples + gapSamples)
        var pos = 0
        tone.copyInto(buf, pos); pos += toneSamples
        gap.copyInto(buf, pos); pos += gapSamples
        tone.copyInto(buf, pos)
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

    /**
     * Stereo STATIC track from a mono cue buffer, with a fixed per-channel
     * gain baked into the interleaved samples. This is how panning is
     * applied without the deprecated per-channel setStereoVolume: each pan
     * bucket is a separate pre-built track. L = stereo[2i], R = stereo[2i+1]
     * - the interleave direction has no unit coverage (Robolectric doesn't
     * expose track PCM), so verify channel direction on-device after editing
     * here. [leftScale]/[rightScale] are <= 1.0, so no clipping.
     */
    private fun makeStereoTrack(mono: ShortArray, leftScale: Float, rightScale: Float): AudioTrack {
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            val s = mono[i].toInt()
            stereo[2 * i]     = (s * leftScale).toInt().toShort()
            stereo[2 * i + 1] = (s * rightScale).toInt().toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(stereo.size * 2, minBuf))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(stereo, 0, stereo.size) }
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

        /** Tail after the last cue's playback before audio focus is
         *  abandoned. Covers AudioTrack finish latency and gives media
         *  apps a clean restore window. */
        private const val ABANDON_SAFETY_MARGIN_MS = 50

        /** Number of discrete pan positions pre-built per pannable cue. Five
         *  is fine enough to track the (capped) pan range, and the rider
         *  can't resolve finer lateralisation from two phone speakers under
         *  helmet + wind anyway. */
        private const val PAN_BUCKETS = 5

        /** Index of the centre bucket; also the [PanResult.Mono] fallback if
         *  a gain pair has no peak. Relies on [BUCKET_LATERAL_POS] being
         *  symmetric about 0. */
        private const val CENTER_BUCKET = PAN_BUCKETS / 2

        /** Representative lateral position of each bucket, evenly spaced over
         *  the full pan range. Must have [PAN_BUCKETS] entries, symmetric. */
        private val BUCKET_LATERAL_POS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

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
