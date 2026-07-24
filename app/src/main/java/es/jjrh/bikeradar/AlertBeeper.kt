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
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates radar alert tones. The vocabulary is organised into three
 * timbre-CLASSES - threat (sharp/high), status (mid ~[STATUS_CARRIER_HZ]),
 * and the descending all-clear - kept apart by carrier band, and WITHIN a
 * class every cue is discriminated by pulse COUNT and TIMING only, never by
 * fine pitch (the noisy-London rule: a rider under helmet + wind + traffic
 * cannot resolve a rising-vs-falling motif or a few-Hz carrier step).
 *
 *   play(1..3) -> 1/2/3 sharp [beepFreqHz] Hz beeps. This is the AWARENESS
 *                   channel: the tier is the vehicle's DISTANCE band - its
 *                   TRUE range, lateral offset included (set by
 *                   AlertDecider, untouched here) - and demands no action; it
 *                   tells the rider what is around, not what to do. Each tier
 *                   carries its own distinct RHYTHM as a redundant fingerprint
 *                   of that same distance band: tier 2 a slow pair
 *                   ([BEEP_GAP_TIER2_MS] ms gap), tier 3 a tight triplet
 *                   ([BEEP_GAP_TIER3_MS] ms gap), so a rider under load tells 2
 *                   from 3 by rhythm as well as count. The rate is a second
 *                   read on the SAME distance, never a speed/urgency code (that
 *                   is the action channel's job); the carrier and the
 *                   count<->distance mapping are constant across tiers.
 *   playClear() -> softer two-tone descent (1100 -> 700 Hz) for "all clear".
 *                   Its own class (a falling glide), never a status carrier.
 *   playUrgent() -> [URGENT_PULSES]-pulse [URGENT_CARRIER_HZ] Hz imminent-impact
 *                   override, LOOMING: each pulse is louder than the last
 *                   ([URGENT_LOOM_START_AMP]..1.0) and the gaps ACCELERATE
 *                   ([URGENT_LOOM_GAP_MS]), so the burst perceptually rushes at
 *                   the rider and shaves brake reaction time (Gray 2011) without
 *                   any pitch motif. Distinct from play(3) by count + cadence.
 *   playRadarDropped() -> [STATUS_CARRIER_HZ] Hz 3-pulse for "rear radar link
 *                   lost mid-ride". Status timbre-class, not a threat, and must
 *                   never read as one.
 *   playRadarReconnected() -> a SINGLE [STATUS_CARRIER_HZ] Hz pulse for "rear
 *                   radar link restored". One pulse vs the drop's three keeps
 *                   the two status cues separable by count, not pitch.
 *
 * The status carrier sits at ~[STATUS_CARRIER_HZ] Hz (up from an earlier
 * 440-660 Hz spread): the old band was masked by traffic/wind low-frequency
 * energy, so "your radar dropped" was inaudible above ~25 km/h and only landed
 * at a red light. Raising the whole class into the ~800-1000 Hz window (still
 * well clear of the sharp threat beeps) makes the status cues carry at speed;
 * it is a timbre-CLASS move, not a fine-pitch distinction.
 *
 * Volume is user-controlled via [setVolumePct] (0..100, default 50). Values
 * map through a perceptual curve so sliding below ~50 actually reduces
 * loudness noticeably. Independently of that app-level gain, every cue also
 * lifts the system alarm stream to a floor above the rider's media level for
 * the duration of the burst (see [applyAlarmFloor]) so a loud podcast can't
 * leave a safety alert at a quiet alarm preset.
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
 * lateralisation. Unknown routes also fall back to mono. The pan is hard
 * (full deflection mutes the opposite channel); safe because both phone
 * speakers are on the bike. Clear chime is always centred (not directional).
 *
 * Failure honesty + self-healing: audio is the primary interface, so this
 * class must never die silently. [onCue] fires only AFTER a play attempt
 * succeeds; a cue that failed to sound is reported through the same hook with
 * the `cue_failed ` prefix, so the capture log and the ride-stats tally never
 * claim a silent cue was heard. When the system audio server dies (a rare but
 * real mid-ride event), every pre-built MODE_STATIC track becomes a dead
 * object that throws on play. The platform's server-state callback is not
 * available to ordinary apps, so the failure itself is the recovery signal: a
 * failed play triggers a rebuild of every track from the retained PCM
 * (throttled to one attempt per [REBUILD_MIN_INTERVAL_MS] so a server that
 * is still down isn't hammered) and one retry on the fresh tracks - the cue
 * still sounds, milliseconds late, once the server is back.
 *
 * The pan-bucket tracks (one stereo track per pannable cue per bucket) are
 * built lazily on the first panned play, not up front: panning is an
 * experimental default-off flag, and the 20 bucket tracks would otherwise
 * triple this class's permanent AudioTrack footprint - a real cost on
 * devices with tight per-output mixer track limits.
 */
class AlertBeeper(
    private val audioManager: AudioManager,
    private val rotationProvider: () -> Int = { Surface.ROTATION_90 },
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    // Monotonic clock for the rebuild throttle; injectable for tests.
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    // Test seam for the one non-mockable step: driving a MODE_STATIC
    // AudioTrack. Robolectric's shadow can't take static PCM writes, so
    // under test every real play "fails"; injecting an outcome lets tests
    // pin the report / rebuild / retry orchestration deterministically.
    // Production always leaves this null and uses the real play path.
    // Same pattern as WalkAwayAlarm's injectable AlarmTone.
    private val playTrackOverride: ((AudioTrack) -> Boolean)? = null,
    // Sibling seam for the stop side, so a test can assert WHICH tracks the
    // urgent cue silences on its way in. Robolectric's shadow does not model
    // playback state, so the stop is otherwise unobservable. Production
    // always leaves this null and calls the real stop path.
    private val stopTrackOverride: ((AudioTrack) -> Unit)? = null,
    // Fires once per successful failure-triggered rebuild, with the new
    // generation. The service wires this to the always-on link journal so a
    // mid-ride audioserver death-and-recovery is reviewable after the ride
    // (the capture log is opt-in and closed between connections, and a cue
    // that healed reports its bare tag - without this hook the recovery
    // would be logcat-only).
    private val onTracksRebuilt: (Int) -> Unit = {},
    // Invoked AFTER the in-call suppression check and the play attempt, on
    // the playback thread. The service wires this to the capture log so a
    // post-ride review shows what the rider actually HEARD (distinct from
    // the decision logs, which record the intent even when the cue is then
    // suppressed). A play that failed reports through the same hook with
    // the `cue_failed ` prefix - the log must never claim a silent cue
    // sounded. Single chokepoint: every play* path goes through it, so a
    // future cue can't ship unlogged.
    private val onCue: (String) -> Unit = {},
    // Media-volume floor crash-repair seam. [applyAlarmFloor] lifts the
    // system alarm stream for the duration of a cue burst and restores it
    // after; if the process dies inside that sub-second window the rider's
    // alarm slider is left raised. These two lambdas let the service persist
    // the pre-lift level (mirroring WalkAwayAlarm's [Prefs.walkAwaySavedAlarmVolume]
    // pattern, but a SEPARATE slot) so the next start repairs a leaked lift.
    // Both default to no-ops: under test and on the standalone audio path the
    // floor still works, it just isn't crash-persisted. Context-free by design,
    // keeping this class injectable like its other seams.
    private val saveAlarmFloor: (Int?) -> Unit = {},
    private val loadAlarmFloor: () -> Int? = { null },
    // One half of the WalkAwayAlarm interlock (the other half is
    // [alarmFloorBaseline], which the walk-away alarm reads): true while the
    // walk-away alarm holds its own STREAM_ALARM override (forced max). While
    // it does, the walk-away path owns the stream - the floor must neither
    // lift (the stream is already at max) nor write its restore (that would
    // yank the blaring walk-away alarm down mid-episode); [restoreAlarmFloor]
    // instead hands the pending restore off to the walk-away's own stop().
    // Defaults to "never active" for tests and beeper-only hosts.
    private val walkAwayOverrideActive: () -> Boolean = { false },
) : CuePlayer {

    private val sampleRate = 44100
    private val beepFreqHz = 3200f
    private val toneDurMs = 80

    /**
     * Inter-pulse gap for a [count]-pulse close-pass beep, in ms. Each tier
     * (= a DISTANCE band, the awareness channel) gets its own rhythm so the
     * tiers are easier to tell apart under load: tier 2 a calm slow pair, tier 3
     * a tight triplet. This is a redundant read on the same distance the count
     * already carries - NOT a speed/urgency code (no beep tier demands action).
     * A single beep has no gap. Both gaps stay above the
     * [AlertBeeperCueShapeTest] silence threshold so pulse-counting stays
     * unambiguous.
     */
    private fun beepGapMs(count: Int): Int = when (count) {
        3 -> BEEP_GAP_TIER3_MS
        else -> BEEP_GAP_TIER2_MS
    }

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

    // Retained PCM for the non-panned cues, so a track killed by an
    // audioserver restart can be rebuilt without regenerating tones.
    private val clearPcm: ShortArray = buildClearPcm()
    private val radarDroppedPcm: ShortArray = buildRadarDroppedPcm()
    private val radarReconnectedPcm: ShortArray = buildRadarReconnectedPcm()

    // Eager tracks (7): the mono cues every rider hears. `var` because an
    // audioserver restart kills the underlying objects and
    // [maybeRebuildTracks] swaps in fresh ones. Written and read on the
    // single playback executor only (plus construction) - [setVolumePct]
    // routes its re-apply through the executor to keep that true.
    private var beepMono: Array<AudioTrack> = Array(3) { i -> makeTrack(beepPcm[i]) }
    private var urgentMono: AudioTrack = makeTrack(urgentPcm)
    private var clearTrack: AudioTrack = makeTrack(clearPcm)
    private var radarDroppedTrack: AudioTrack = makeTrack(radarDroppedPcm)
    private var radarReconnectedTrack: AudioTrack = makeTrack(radarReconnectedPcm)

    // Pan path (lazy, up to 20 tracks): one stereo track per pannable cue per
    // bucket, built on the first panned play that needs the row and dropped
    // on rebuild. Executor-confined like the eager tracks.
    private val beepBucketRows: Array<Array<AudioTrack>?> = arrayOfNulls(3)
    private var urgentBucketRow: Array<AudioTrack>? = null

    /** Monotonic time of the last rebuild attempt, successful or not; gates
     *  the once-per-interval throttle. Null = never attempted. Executor-confined. */
    private var lastRebuildAttemptMs: Long? = null

    /** Set by [release]'s executor-marshalled teardown. Once set, cue
     *  attempts and rebuilds are refused: a cue racing service destroy must
     *  not resurrect fresh AudioTracks that nothing would ever release.
     *  Executor-confined. */
    private var released = false

    /** Bumped on every successful failure-triggered rebuild; lets tests pin
     *  that a rebuild actually replaced the track set. */
    internal var trackGeneration: Int = 0
        private set

    /** True once any lazy pan-bucket track exists; pins the laziness
     *  contract in tests (panning off must build zero bucket tracks). */
    internal val panBucketsBuilt: Boolean
        get() = urgentBucketRow != null || beepBucketRows.any { it != null }

    // Track-duration table for the abandon-timer. Computed at build time
    // from the same sample counts the AudioTrack contents use, so the
    // timer never under-shoots the actual playback.
    private val beepDurationMs: IntArray = IntArray(3) { i ->
        val count = i + 1
        count * toneDurMs + (count - 1) * beepGapMs(count)
    }
    private val clearDurationMs: Int = 110 + 60 + 110
    private val urgentDurationMs: Int = URGENT_PULSES * URGENT_TONE_MS + URGENT_LOOM_GAP_MS.sum()
    private val radarDroppedDurationMs: Int = 3 * 130 + 2 * 90
    private val radarReconnectedDurationMs: Int = RECONNECT_TONE_MS

    @Volatile private var volumePct = DEFAULT_VOLUME_PCT

    @Volatile private var panningEnabled: Boolean = false

    @Volatile private var invertLR: Boolean = false

    @Volatile private var hasHeadphoneRoute: Boolean = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoute()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoute()
        }
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
                    .build(),
            )
            // Empty listener: close-pass beeps are fire-and-forget. Loss
            // events are not actionable - the cue is already buffered to
            // the audio HAL by the time any LOSS callback would land.
            .setOnAudioFocusChangeListener { }
            .build()

    @Volatile private var hasFocus: Boolean = false

    /** The rider's own STREAM_ALARM index, captured once when [applyAlarmFloor]
     *  first lifts the alarm stream for a cue burst; null when no lift is in
     *  effect. Restored when the abandon timer fires (end of the burst) and in
     *  [release]. @Volatile because it is written on the playback executor
     *  ([applyAlarmFloor]) and read/cleared on the main looper (the abandon
     *  runnable) - the same cross-thread shape as [hasFocus]. */
    @Volatile private var savedAlarmFloor: Int? = null

    private val abandonHandler = Handler(Looper.getMainLooper())
    private val abandonRunnable = Runnable {
        if (hasFocus) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest)
            } catch (_: Throwable) {}
            hasFocus = false
        }
        restoreAlarmFloor()
    }

    init {
        applyVolume()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshRoute()
        repairLeakedAlarmFloor()
    }

    override fun play(beeps: Int, lateralPos: Float) {
        val idx = beeps - 1
        if (idx !in 0..2) return
        val durationMs = beepDurationMs.getOrNull(idx) ?: return
        executor.execute {
            if (suppressForCall()) return@execute
            report("beep count=$beeps") {
                playPanned(beepMono[idx], { beepBucketRow(idx) }, durationMs, lateralPos)
            }
        }
    }

    override fun playClear() {
        executor.execute {
            if (suppressForCall()) return@execute
            report("clear") {
                // Clear is non-directional. Always mono.
                clearTrack.setVolume(currentMonoGain())
                playWithFocus(clearTrack, clearDurationMs)
            }
        }
    }

    override fun playUrgent(lateralPos: Float) {
        executor.execute {
            if (suppressForCall()) return@execute
            silenceBeeps()
            report("urgent") {
                playPanned(urgentMono, { urgentBucketRow() }, urgentDurationMs, lateralPos)
            }
        }
    }

    /**
     * Silence any still-sounding awareness beep before the urgent cue starts.
     *
     * Every cue owns its own [AudioTrack] and [playOnce] stops only the track
     * it is about to play, so the cues mix rather than pre-empt. An urgent
     * landing inside a beep's pattern (tier 2 spans ~310 ms, tier 3 ~380 ms)
     * would otherwise sound smeared into it. The action channel outranks the
     * awareness channel, so the urgent is the cue that has to arrive intact.
     *
     * The truncated beep is not re-stated. It had already fired, so it set its
     * per-track tier latch in AlertDecider, and a same-tier re-fire on that
     * track stays silent until the tier rises or the all-clear clears the
     * latch. The trade is deliberate: the rider gets the act-now cue in place
     * of part of a distance-band cue that demands no action.
     *
     * Executor-confined like every other cue path. `stop()` on a track that
     * was never playing throws and says nothing useful, so it is ignored -
     * same contract as the pre-play stop in [playOnce].
     */
    private fun silenceBeeps() {
        // Teardown runs on this same executor, so a cue submitted around
        // release() can land behind it. Every other track access checks this
        // first; stopping a released track only survives because release()
        // leaves it throwing the exception below.
        if (released) return
        val live = beepMono.asSequence() +
            beepBucketRows.filterNotNull().flatMap { it.asIterable() }
        val stop: (AudioTrack) -> Unit = stopTrackOverride ?: {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
        }
        live.forEach(stop)
    }

    /** Rear-radar dropped status cue: the radar link went down mid-ride, so
     *  rear awareness is lost. Non-directional (mono). A low 3-pulse, a
     *  distinct count + timbre-class from the sharp/high threat beeps - a
     *  status cue, never a threat. */
    override fun playRadarDropped() {
        executor.execute {
            if (suppressForCall()) return@execute
            report("radar_drop") {
                radarDroppedTrack.setVolume(currentMonoGain())
                playWithFocus(radarDroppedTrack, radarDroppedDurationMs)
            }
        }
    }

    /** Rear-radar reconnected status cue: the dropped link is back, so rear
     *  awareness is restored. A SINGLE soft pulse - the count of one separates
     *  it from the drop cue's three, so the rider reads it by count, not fine
     *  pitch. Non-directional (mono). Fired once
     *  per down-episode, and only after a drop cue was raised (the caller gates
     *  this via [RadarDropDecider]); a cold-start connect stays silent. */
    override fun playRadarReconnected() {
        executor.execute {
            if (suppressForCall()) return@execute
            report("radar_reconnect") {
                radarReconnectedTrack.setVolume(currentMonoGain())
                playWithFocus(radarReconnectedTrack, radarReconnectedDurationMs)
            }
        }
    }

    /**
     * Run [attempt] and report the outcome through [onCue]: the bare [tag]
     * when the cue actually made it to an AudioTrack.play() that did not
     * throw, `cue_failed <tag>` otherwise. A first failure means the track
     * set is probably dead (audioserver restart kills every MODE_STATIC
     * track), so it triggers one throttled rebuild from the retained PCM and
     * retries the cue on the fresh tracks - re-running [attempt] re-reads
     * the track fields, so the retry picks up the rebuilt objects.
     * Executor-only.
     */
    private inline fun report(tag: String, attempt: () -> Boolean) {
        if (released) return
        var played = attempt()
        if (!played && maybeRebuildTracks()) played = attempt()
        onCue(if (played) tag else CUE_FAILED_PREFIX + tag)
    }

    /**
     * Failure-triggered self-heal: release whatever is left of the current
     * track set and rebuild it from the retained PCM. Throttled to one
     * attempt per [REBUILD_MIN_INTERVAL_MS] - while the audioserver is still
     * down every attempt fails, and hammering a restarting server helps
     * nobody. Returns true when a fresh track set is in place. Runs on the
     * playback executor so it cannot interleave a play.
     */
    private fun maybeRebuildTracks(): Boolean {
        if (released) return false
        val now = clock()
        val last = lastRebuildAttemptMs
        if (last != null && now - last < REBUILD_MIN_INTERVAL_MS) return false
        lastRebuildAttemptMs = now
        return try {
            releaseAllTracks()
            beepMono = Array(3) { i -> makeTrack(beepPcm[i]) }
            urgentMono = makeTrack(urgentPcm)
            clearTrack = makeTrack(clearPcm)
            radarDroppedTrack = makeTrack(radarDroppedPcm)
            radarReconnectedTrack = makeTrack(radarReconnectedPcm)
            applyVolume()
            trackGeneration++
            Log.i(TAG, "audio tracks rebuilt after a play failure (gen=$trackGeneration)")
            onTracksRebuilt(trackGeneration)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "audio track rebuild failed: $t")
            false
        }
    }

    /** Lazy pan-bucket row for beep cue [idx], built on first panned use. */
    private fun beepBucketRow(idx: Int): Array<AudioTrack> = beepBucketRows[idx] ?: Array(PAN_BUCKETS) { b ->
        makeStereoTrack(beepPcm[idx], bucketScales[b].first, bucketScales[b].second)
    }.also { beepBucketRows[idx] = it }

    /** Lazy pan-bucket row for the urgent cue, built on first panned use. */
    private fun urgentBucketRow(): Array<AudioTrack> = urgentBucketRow ?: Array(PAN_BUCKETS) { b ->
        makeStereoTrack(urgentPcm, bucketScales[b].first, bucketScales[b].second)
    }.also { urgentBucketRow = it }

    fun setVolumePct(pct: Int) {
        volumePct = pct.coerceIn(0, 100)
        // Track objects are executor-confined; apply the new gain there so
        // a Settings change can never race a play or a rebuild.
        executor.execute { applyVolume() }
    }

    fun setPanning(enabled: Boolean, invertLR: Boolean) {
        this.panningEnabled = enabled
        this.invertLR = invertLR
    }

    fun release() {
        abandonHandler.removeCallbacks(abandonRunnable)
        if (hasFocus) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest)
            } catch (_: Throwable) {}
            hasFocus = false
        }
        // The abandon timer that would normally restore the alarm floor was
        // just cancelled; do it here so service destroy never leaves the
        // rider's alarm slider raised.
        restoreAlarmFloor()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        // Teardown rides the playback executor, keeping the track fields
        // executor-confined: a cue task already queued ahead of this one
        // finds `released` set and skips, so the failure-triggered rebuild
        // can never run after release and leak fresh tracks. shutdown()
        // still runs everything queued before it.
        try {
            executor.execute {
                released = true
                releaseAllTracks()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Second release(): the executor is already shut down and the
            // teardown task already ran.
        }
        if (executor is java.util.concurrent.ExecutorService) executor.shutdown()
    }

    /** Release every live track, eager and lazily-built alike. Each release
     *  is individually guarded: after an audioserver death the objects are
     *  already invalid and may object to the farewell. */
    private fun releaseAllTracks() {
        val all = beepMono.asSequence() +
            sequenceOf(urgentMono, clearTrack, radarDroppedTrack, radarReconnectedTrack) +
            beepBucketRows.filterNotNull().flatMap { it.asIterable() } +
            (urgentBucketRow?.asSequence() ?: emptySequence())
        all.forEach {
            try {
                it.release()
            } catch (_: Throwable) {}
        }
        beepBucketRows.fill(null)
        urgentBucketRow = null
    }

    /**
     * In-call guard, covering both call classes. When a call holds audio
     * focus EXCLUSIVE, USAGE_ALARM plays can be silenced at the speaker
     * mid-call without indication - and on some OEMs alarm-usage audio
     * behaves unpredictably while a call routes the output. Skipping the
     * audio path entirely preserves call audio integrity; the visual
     * overlay and (future) wrist haptic still fire.
     *
     * MODE_IN_CALL is telephony; MODE_IN_COMMUNICATION is VoIP (WhatsApp,
     * Meet, Telegram, SIP) - the same rider situation, so both suppress.
     */
    private fun suppressForCall(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun playWithFocus(track: AudioTrack, durationMs: Int): Boolean {
        if (!hasFocus) {
            val granted = try {
                audioManager.requestAudioFocus(focusRequest) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (_: Throwable) {
                false
            }
            hasFocus = granted
        }
        applyAlarmFloor()
        val played = (playTrackOverride ?: ::playOnce)(track)
        // Re-arm the abandon timer. Back-to-back plays extend the
        // window so media stays ducked across the burst rather than
        // restoring + re-ducking between cues.
        abandonHandler.removeCallbacks(abandonRunnable)
        abandonHandler.postDelayed(abandonRunnable, (durationMs + ABANDON_SAFETY_MARGIN_MS).toLong())
        return played
    }

    /**
     * Media-volume floor. Before a cue sounds, lift the system alarm stream so
     * the alert sits ~[ALARM_MARGIN_STEPS] steps (~6 dB) above whatever the
     * rider set their MEDIA volume to, without ever turning the alarm DOWN
     * below their own preset. USAGE_ALARM cues play on STREAM_ALARM, so a rider
     * with a loud podcast (STREAM_MUSIC) but a quiet alarm slider would
     * otherwise hear a safety alert too faintly. No microphone, no privacy
     * cost: STREAM_MUSIC is the rider's own chosen listening level, used as a
     * proxy for ambient loudness. Best-effort - some OEMs reject volume writes
     * from background services, which is fine; the app-level track gain still
     * plays.
     *
     * Burst-scoped: the pre-lift level is saved ONCE (guarded by
     * [savedAlarmFloor] being null) and restored when the burst's abandon timer
     * fires, so a rapid multi-beep burst lifts and restores once, not per
     * pulse. The saved level is persisted via [saveAlarmFloor] so a process
     * death inside the sub-second lift window is repaired at the next start
     * ([repairLeakedAlarmFloor]) - the walk-away alarm's pattern, on a separate
     * slot. Executor-confined (called from [playWithFocus]).
     *
     * Interlock: while the walk-away alarm holds its own override
     * ([walkAwayOverrideActive]) the floor does nothing - the stream is
     * already forced to max, and writing floor state during someone else's
     * override would corrupt whose "original" gets restored.
     */
    private fun applyAlarmFloor() {
        if (walkAwayOverrideActive()) return
        try {
            val musicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val alarmMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            // Baseline = the rider's true level: the already-saved original
            // during a burst, else the current (un-lifted) alarm index.
            val baseline = savedAlarmFloor
                ?: audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = computeAlarmFloorIndex(musicVol, musicMax, baseline, alarmMax)
            if (target <= baseline) return
            if (savedAlarmFloor == null) {
                savedAlarmFloor = baseline
                saveAlarmFloor(baseline)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "alarm-floor lift failed: $t")
        }
    }

    /** Restore the alarm stream to the rider's pre-lift level. No-op when no
     *  lift is in effect. Runs on the main looper (abandon timer) and on the
     *  caller thread ([release]); the null-guard makes the second call inert,
     *  and [savedAlarmFloor] is @Volatile, so the cross-thread overlap with an
     *  executor-side [applyAlarmFloor] is benign - worst case a lift is
     *  restored a burst early and re-applied by the next cue, and a leak is
     *  still caught by the crash-repair slot.
     *
     *  Interlock: if the walk-away alarm took the stream over mid-lift
     *  (ordering: floor lifts -> walk-away forces max -> this timer fires),
     *  the stream write is SKIPPED and only the slot is cleared - the
     *  walk-away captured the rider's true baseline through
     *  [alarmFloorBaseline] at its start() and now owns the restore. Writing
     *  here would yank the blaring walk-away alarm down mid-episode. */
    private fun restoreAlarmFloor() {
        val saved = savedAlarmFloor ?: return
        if (!walkAwayOverrideActive()) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "alarm-floor restore failed: $t")
            }
        }
        savedAlarmFloor = null
        saveAlarmFloor(null)
    }

    /**
     * The rider's true pre-lift STREAM_ALARM level while the media-volume
     * floor holds a lift, else null. The WalkAwayAlarm interlock: when the
     * walk-away alarm starts while a cue burst has the alarm stream lifted,
     * reading the CURRENT stream volume would capture the lifted level as
     * "the rider's original" and strand the slider there after both restores.
     * WalkAwayAlarm.start() consults this first and saves the true baseline
     * instead; [restoreAlarmFloor] then hands the restore off to walk-away's
     * stop(). @Volatile-backed, safe from any thread.
     */
    internal fun alarmFloorBaseline(): Int? = savedAlarmFloor

    /** Repair an alarm-floor lift leaked by a process death mid-burst: a
     *  persisted level ([loadAlarmFloor]) means the previous process raised the
     *  alarm stream and died before restoring. No cue can be active at
     *  construction, so restoring unconditionally is safe. */
    private fun repairLeakedAlarmFloor() {
        val leaked = loadAlarmFloor() ?: return
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, leaked.coerceIn(0, max), 0)
            Log.i(TAG, "restored alarm volume leaked by a mid-cue process death")
        } catch (t: Throwable) {
            Log.w(TAG, "leaked alarm-floor restore failed: $t")
        }
        saveAlarmFloor(null)
    }

    /**
     * Pure target-index computation for the media-volume floor. STREAM_MUSIC
     * and STREAM_ALARM have independent step counts, so the music index is
     * scaled into the alarm range, then [ALARM_MARGIN_STEPS] (~6 dB headroom) is
     * added. The result is FLOORED at [alarmVol] (never turn the rider's alarm
     * down) and capped at [alarmMax]. When no media is playing ([musicVol] == 0)
     * the rider's own alarm level stands - the floor lifts above MEDIA, nothing
     * else.
     * Volume steps are not perfectly uniform in dB, so the margin is an honest
     * approximation, not a calibrated +6 dB.
     */
    internal fun computeAlarmFloorIndex(
        musicVol: Int,
        musicMax: Int,
        alarmVol: Int,
        alarmMax: Int,
    ): Int {
        if (alarmMax <= 0 || musicMax <= 0) return alarmVol
        if (musicVol <= 0) return alarmVol
        val musicScaledToAlarm = ceil(musicVol.toDouble() / musicMax * alarmMax).toInt()
        val target = musicScaledToAlarm + ALARM_MARGIN_STEPS
        return target.coerceIn(alarmVol, alarmMax)
    }

    /**
     * One play attempt. Returns false when the track objects to play(), so
     * the caller can report the failure through [onCue] and trigger the
     * self-heal - a dead audio path must surface, never masquerade as a
     * healthy one. The pre-play stop() is allowed to fail quietly: a track
     * that was never playing throws there without saying anything about
     * whether it can play.
     */
    private fun playOnce(track: AudioTrack): Boolean {
        try {
            track.stop()
        } catch (_: IllegalStateException) {}
        return try {
            track.setPlaybackHeadPosition(0)
            track.play()
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cue play failed: $e")
            false
        }
    }

    private fun applyVolume() {
        val g = currentMonoGain()
        beepMono.forEach { it.setVolume(g) }
        beepBucketRows.filterNotNull().forEach { row -> row.forEach { it.setVolume(g) } }
        urgentMono.setVolume(g)
        urgentBucketRow?.forEach { it.setVolume(g) }
        clearTrack.setVolume(g)
        radarDroppedTrack.setVolume(g)
        radarReconnectedTrack.setVolume(g)
    }

    private fun currentMonoGain(): Float {
        val linear = volumePct / 100f
        return linear * linear
    }

    /**
     * Hard pan formula on [lateralPos] in [-1, +1]: full deflection mutes
     * the opposite channel.
     *  -1 -> (1.0, 0.0) full left
     *   0 -> (1.0, 1.0) centred (both channels full)
     *  +1 -> (0.0, 1.0) full right
     * Hard rather than capped because the audio always comes from the
     * phone's two built-in speakers (never headphones), so there is no
     * silent-ear risk - both speakers are on the bike. The previous ~3 dB
     * bias was too subtle to localise.
     */
    internal fun computePan(lateralPos: Float): Pair<Float, Float> {
        val clamped = lateralPos.coerceIn(-1f, 1f)
        val left = (1f - clamped).coerceAtMost(1f)
        val right = (1f + clamped).coerceAtMost(1f)
        return left to right
    }

    /**
     * Play the cue with stereo panning. [resolvePan] stays the decision
     * authority (route / rotation / invert / volume); a [PanResult.Mono]
     * plays the plain mono track, a [PanResult.Stereo] maps to the nearest
     * pan bucket - built lazily via [buckets] on the first stereo play, so
     * riders who never enable panning never pay for 20 extra AudioTracks.
     * The absolute level is applied with the non-deprecated
     * [AudioTrack.setVolume] (uniform). Because each bucket's peak channel
     * is normalised to 1.0, playing the chosen bucket at
     * `setVolume(max(left, right))` reproduces resolvePan's gains.
     */
    private fun playPanned(
        monoTrack: AudioTrack,
        buckets: () -> Array<AudioTrack>,
        durationMs: Int,
        lateralPos: Float,
    ): Boolean {
        val track: AudioTrack
        val level: Float
        when (
            val result = resolvePan(
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
            )
        ) {
            is PanResult.Mono -> {
                track = monoTrack
                level = result.gain
            }
            is PanResult.Stereo -> {
                track = buckets()[nearestPanBucket(result.left, result.right)]
                level = maxOf(result.left, result.right)
            }
        }
        track.setVolume(level)
        return playWithFocus(track, durationMs)
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
            if (d < bestDist) {
                bestDist = d
                best = k
            }
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

    internal fun buildBeepPcm(count: Int): ShortArray {
        val toneSamples = sampleRate * toneDurMs / 1000
        val gapSamples = sampleRate * beepGapMs(count) / 1000
        val tone = generateTone(toneSamples, beepFreqHz)
        val gap = ShortArray(gapSamples)

        val buf = ShortArray(count * toneSamples + (count - 1) * gapSamples)
        var pos = 0
        repeat(count) { i ->
            tone.copyInto(buf, pos)
            pos += toneSamples
            if (i < count - 1) {
                gap.copyInto(buf, pos)
                pos += gapSamples
            }
        }
        return buf
    }

    internal fun buildClearPcm(): ShortArray {
        val toneSamples = sampleRate * 110 / 1000
        val gapSamples = sampleRate * 60 / 1000
        val hi = generateTone(toneSamples, 1100f)
        val lo = generateTone(toneSamples, 700f)
        val gap = ShortArray(gapSamples)
        val buf = ShortArray(hi.size + gap.size + lo.size)
        var pos = 0
        hi.copyInto(buf, pos)
        pos += hi.size
        gap.copyInto(buf, pos)
        pos += gap.size
        lo.copyInto(buf, pos)
        return buf
    }

    internal fun buildUrgentPcm(): ShortArray {
        // [URGENT_PULSES] pulses at [URGENT_CARRIER_HZ], LOOMING: each pulse is
        // louder than the last ([URGENT_LOOM_START_AMP] -> 1.0, linear) and the
        // gaps ACCELERATE ([URGENT_LOOM_GAP_MS], monotonically shrinking), so
        // the burst perceptually rushes at the rider - a loudness+tempo loom
        // that saves brake reaction time (Gray 2011) without a pitch motif. The
        // higher carrier + count + rising rate keep it unmistakably NOT a normal
        // close-approach beep. The loom is baked into the mono PCM, so the
        // stereo pan buckets inherit it unchanged.
        val toneSamples = sampleRate * URGENT_TONE_MS / 1000
        val gapSampleCounts = URGENT_LOOM_GAP_MS.map { sampleRate * it / 1000 }
        val totalSamples = URGENT_PULSES * toneSamples + gapSampleCounts.sum()
        val buf = ShortArray(totalSamples)
        var pos = 0
        repeat(URGENT_PULSES) { i ->
            val ampScale = URGENT_LOOM_START_AMP +
                (1f - URGENT_LOOM_START_AMP) * i / (URGENT_PULSES - 1)
            generateTone(toneSamples, URGENT_CARRIER_HZ, ampScale).copyInto(buf, pos)
            pos += toneSamples
            if (i < URGENT_PULSES - 1) {
                pos += gapSampleCounts[i] // gap left as silence (zeros)
            }
        }
        return buf
    }

    internal fun buildRadarDroppedPcm(): ShortArray {
        // Status-carrier ([STATUS_CARRIER_HZ]) 3-pulse. The mid carrier keeps it
        // in the status timbre-class (not a sharp/high threat beep) yet high
        // enough to carry over traffic/wind, so a dead radar is heard at speed,
        // not just at a red light; the count of THREE separates it from the
        // reconnect cue's single pulse (the rider discriminates by count, not
        // fine pitch, per the noisy-London rule).
        val toneSamples = sampleRate * 130 / 1000
        val gapSamples = sampleRate * 90 / 1000
        val tone = generateTone(toneSamples, STATUS_CARRIER_HZ)
        val gap = ShortArray(gapSamples)
        val count = 3
        val buf = ShortArray(count * toneSamples + (count - 1) * gapSamples)
        var pos = 0
        repeat(count) { i ->
            tone.copyInto(buf, pos)
            pos += toneSamples
            if (i < count - 1) {
                gap.copyInto(buf, pos)
                pos += gapSamples
            }
        }
        return buf
    }

    internal fun buildRadarReconnectedPcm(): ShortArray {
        // A SINGLE status-carrier ([STATUS_CARRIER_HZ]) pulse. The count of ONE
        // is the discriminator - the radar-drop cue is THREE pulses on the same
        // carrier, so a single status-class pulse reads as "rear radar link
        // restored" by count, not fine pitch (the noisy-London rule). Shares the
        // drop cue's carrier deliberately: the two are the same event's
        // down/back pair, told apart by count, and there is no motif to mishear
        // (it is one tone).
        val toneSamples = sampleRate * RECONNECT_TONE_MS / 1000
        return generateTone(toneSamples, STATUS_CARRIER_HZ)
    }

    /**
     * One sine burst. [ampScale] (<= 1.0) scales the whole pulse's peak; the
     * looming urgent cue rides this to make each successive pulse louder
     * without touching pitch. A short raised-cosine-ish fade at both ends
     * avoids the click of a hard start/stop.
     */
    private fun generateTone(numSamples: Int, freqHz: Float, ampScale: Float = 1f): ShortArray {
        val buf = ShortArray(numSamples)
        val twoPiF = 2.0 * PI * freqHz / sampleRate
        val fade = (numSamples * 0.08).toInt().coerceAtLeast(1)
        for (i in buf.indices) {
            val env = min(
                min(i.toDouble() / fade, (numSamples - 1 - i).toDouble() / fade),
                1.0,
            )
            buf[i] = (Short.MAX_VALUE * 0.75 * ampScale * env * sin(twoPiF * i)).toInt().toShort()
        }
        return buf
    }

    /**
     * Interleave a mono cue into stereo PCM with a per-channel gain baked in:
     * out[2i] = left, out[2i+1] = right. Pure and side-effect-free, so the
     * channel order and gain are unit-tested directly (Robolectric doesn't
     * expose AudioTrack PCM, so the test exercises this function rather than
     * the built track). [leftScale]/[rightScale] are <= 1.0, so no clipping.
     */
    internal fun interleaveStereo(mono: ShortArray, leftScale: Float, rightScale: Float): ShortArray {
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            val s = mono[i].toInt()
            stereo[2 * i] = (s * leftScale).toInt().toShort()
            stereo[2 * i + 1] = (s * rightScale).toInt().toShort()
        }
        return stereo
    }

    /**
     * Stereo STATIC track from a mono cue buffer, panned by [interleaveStereo].
     * Each pan bucket is a separate pre-built track - this is how panning is
     * applied without the deprecated per-channel setStereoVolume.
     */
    private fun makeStereoTrack(mono: ShortArray, leftScale: Float, rightScale: Float): AudioTrack {
        val stereo = interleaveStereo(mono, leftScale, rightScale)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(stereo.size * 2, minBuf))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(stereo, 0, stereo.size) }
    }

    private fun makeTrack(buf: ShortArray): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(buf, 0, buf.size) }
    }

    companion object {
        const val DEFAULT_VOLUME_PCT = 50

        private const val TAG = "BikeRadar"

        /** Carrier for the whole status timbre-class (radar-drop, reconnect).
         *  Chosen in the ~800-1000 Hz window: the earlier
         *  440-660 Hz status band sat in the traffic/wind low-frequency mask,
         *  so a dead-radar cue was inaudible above ~25 km/h; ~900 Hz carries at
         *  speed while staying a full timbre-class below the sharp threat beeps
         *  (3200/3800 Hz). This is a timbre-CLASS move (a whole band shift),
         *  not a fine-pitch distinction; the wind/traffic mask peaks in the low
         *  band, so lifting the class clear of it is what buys audibility at
         *  speed. Status cues stay discriminated by COUNT (1/2/3), never by a
         *  step off this carrier. */
        internal const val STATUS_CARRIER_HZ = 900f

        /** Inter-pulse gap for a 2-pulse close-pass beep (slow, calm pair). */
        internal const val BEEP_GAP_TIER2_MS = 150

        /** Inter-pulse gap for a 3-pulse close-pass beep (tight triplet).
         *  Shorter than [BEEP_GAP_TIER2_MS] purely to give the tiers distinct
         *  rhythms so they are easier to tell apart under load - a redundant
         *  fingerprint of the DISTANCE band the count already carries, NOT a
         *  speed/urgency code (no beep tier demands action; that is the urgent
         *  action channel's job) and NOT a change to what distance maps to which
         *  tier. Both gaps stay well above the [AlertBeeperCueShapeTest]
         *  pulse-silence threshold. Values chosen here (no decision-doc figure);
         *  tune on ride evidence. */
        internal const val BEEP_GAP_TIER3_MS = 70

        /** Pulse count of the imminent-impact urgent cue (unchanged; pinned by
         *  [AlertBeeperCueShapeTest]). */
        internal const val URGENT_PULSES = 4

        /** Per-pulse tone length of the urgent cue, ms. */
        internal const val URGENT_TONE_MS = 70

        /** Urgent-cue carrier (unchanged; above the close-pass 3200 Hz). */
        internal const val URGENT_CARRIER_HZ = 3800f

        /** Amplitude of the FIRST urgent pulse relative to peak; the loom ramps
         *  linearly from here to 1.0 across the burst so each pulse is louder
         *  than the last. A looming-intensity envelope shortens brake reaction
         *  time (Gray, "Looming Auditory Collision Warnings for Driving", Human
         *  Factors 2011) - loudness + tempo, no pitch motif. Value chosen here;
         *  tune on rides. */
        internal const val URGENT_LOOM_START_AMP = 0.55f

        /** The 3 inter-pulse gaps of the 4-pulse urgent burst, ms, in order.
         *  Monotonically SHRINKING so the burst accelerates (the tempo half of
         *  the loom). Values chosen here; tune on rides. */
        internal val URGENT_LOOM_GAP_MS = intArrayOf(70, 50, 30)

        /** Approx headroom the media-volume floor adds above the scaled media
         *  level, in alarm-stream steps (~6 dB; steps are not uniform in dB, so
         *  this is an honest approximation, not a calibrated figure). */
        internal const val ALARM_MARGIN_STEPS = 2

        /** Prepended to a cue's [onCue] tag when the play attempt failed even
         *  after the rebuild-and-retry. Consumers key off the bare tags
         *  ("beep…", "urgent") for the sounded-alert tally, so failed cues
         *  are excluded from it by construction; the capture log keeps the
         *  full line for post-ride diagnosis. */
        const val CUE_FAILED_PREFIX = "cue_failed "

        /** Floor between failure-triggered track rebuilds. Long enough not
         *  to hammer an audioserver that is still restarting (recovery is
         *  typically 1-5 s), short enough that the primary alert channel is
         *  not left visual-only for long after the server returns - a
         *  rebuild is cheap (8 small tracks from retained PCM). */
        internal const val REBUILD_MIN_INTERVAL_MS = 3_000L

        /** Single-pulse duration of the radar-reconnect cue, in ms. Doubles as
         *  the cue's total length for the abandon-timer (one tone, no gaps). */
        private const val RECONNECT_TONE_MS = 150

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
