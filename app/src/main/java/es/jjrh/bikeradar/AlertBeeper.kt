// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.PI
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
 * Every cue is MONO. Directional (stereo-panned) cues were built and then
 * removed - see the note at the end of this doc before proposing them again.
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
 * **Directional (stereo-panned) cues were removed, and re-proposing them
 * needs new evidence rather than a new idea.** The feature worked - the
 * buffers were sample-aligned, the pan depth was chosen by ear against a
 * sweep - and it was still deleted, for reasons that live in the platform
 * rather than in this class:
 *
 *   - **On the phone's own speakers it did nothing.** Cues declared here
 *     reach ONE driver, while a video on the same phone drives both, so a
 *     panned cue was indistinguishable left from right. That is the output
 *     riders actually use.
 *   - **On headphones it made a pre-existing platform behaviour obvious.**
 *     With a headset connected the phone plays every cue on its own speaker
 *     TOO, and Bluetooth latency puts the two copies a couple of hundred ms
 *     apart. Mono cues smear; hard-panned copies land on opposite ears and
 *     read as an infuriating echo.
 *
 * Note the echo is NOT a panning bug and did NOT leave with panning - it was
 * there on every cue with the headsets tried on this phone, panned or not.
 *
 * Both findings were established by control, not inference, and the controls
 * are the part worth repeating before re-opening any of it: the cue PCM
 * written out to file and played from a media player pans correctly and stays
 * in sync on the same phone and the same headsets, and a video in landscape
 * drives both speakers. So the buffers, the headsets and the hardware are all
 * exonerated, and what differs is how this class plays a cue - `USAGE_ALARM`
 * on a short reused MODE_STATIC track. Which half of that is responsible is
 * NOT established.
 *
 * The cheap check that settles an apparent inter-channel delay, before a line
 * of this class is touched: is the phone ALSO making the sound?
 */
class AlertBeeper(
    private val audioManager: AudioManager,
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

    // Cue PCM, built once and retained so a track killed by an audioserver
    // restart can be rebuilt without regenerating tones.
    private val beepPcm: Array<ShortArray> = Array(3) { i -> buildBeepPcm(i + 1) }
    private val urgentPcm: ShortArray = buildUrgentPcm()

    // Retained PCM for the remaining cues, so a track killed by an
    // audioserver restart can be rebuilt without regenerating tones.
    private val clearPcm: ShortArray = buildClearPcm()
    private val radarDroppedPcm: ShortArray = buildRadarDroppedPcm()
    private val radarReconnectedPcm: ShortArray = buildRadarReconnectedPcm()

    // One track per cue, built at construction. `var` because an
    // audioserver restart kills the underlying objects and
    // [maybeRebuildTracks] swaps in fresh ones. Written and read on the
    // single playback executor only (plus construction) - [setVolumePct]
    // routes its re-apply through the executor to keep that true.
    private var beepMono: Array<AudioTrack> = Array(3) { i -> makeTrack(beepPcm[i]) }
    private var urgentMono: AudioTrack = makeTrack(urgentPcm)
    private var clearTrack: AudioTrack = makeTrack(clearPcm)
    private var radarDroppedTrack: AudioTrack = makeTrack(radarDroppedPcm)
    private var radarReconnectedTrack: AudioTrack = makeTrack(radarReconnectedPcm)

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
        repairLeakedAlarmFloor()
    }

    override fun play(beeps: Int) {
        val idx = beeps - 1
        if (idx !in 0..2) return
        val durationMs = beepDurationMs.getOrNull(idx) ?: return
        executor.execute {
            if (suppressForCall()) return@execute
            report("beep count=$beeps") {
                beepMono[idx].setVolume(currentMonoGain())
                playWithFocus(beepMono[idx], durationMs)
            }
        }
    }

    override fun playClear() {
        executor.execute {
            if (suppressForCall()) return@execute
            report("clear") {
                clearTrack.setVolume(currentMonoGain())
                playWithFocus(clearTrack, clearDurationMs)
            }
        }
    }

    override fun playUrgent() {
        executor.execute {
            if (suppressForCall()) return@execute
            silenceBeeps()
            report("urgent") {
                urgentMono.setVolume(currentMonoGain())
                playWithFocus(urgentMono, urgentDurationMs)
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
        val live = beepMono.asSequence()
        val stop: (AudioTrack) -> Unit = stopTrackOverride ?: {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
        }
        live.forEach(stop)
    }

    /** Rear-radar dropped status cue: the radar link went down mid-ride, so
     *  rear awareness is lost. A low 3-pulse, a
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
     *  pitch. Fired once
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
        var played = attemptOrFailed(attempt)
        if (!played && maybeRebuildTracks()) played = attemptOrFailed(attempt)
        onCue(if (played) tag else CUE_FAILED_PREFIX + tag)
    }

    /**
     * Run [attempt], turning a throw into an ordinary failed cue.
     *
     * A track can throw on play when the audioserver is down or the device is
     * at its mixer-track limit - the very conditions the rebuild path exists
     * for. Letting that escape would take the cue out of [report] entirely: no
     * `cue_failed` line, so the capture log would show no cue at all rather
     * than a silent one, and no rebuild attempt. Audio is the primary
     * interface; a lost cue must still be a REPORTED lost cue.
     */
    private inline fun attemptOrFailed(attempt: () -> Boolean): Boolean = try {
        attempt()
    } catch (t: Throwable) {
        // Pass the throwable, not its string: this is the one path that has
        // just stopped producing a crash report, so keep the stack trace.
        Log.w(TAG, "cue attempt threw", t)
        false
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

    fun setVolumePct(pct: Int) {
        volumePct = pct.coerceIn(0, 100)
        // Track objects are executor-confined; apply the new gain there so
        // a Settings change can never race a play or a rebuild.
        executor.execute { applyVolume() }
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

    /** Release every live track. Each release is individually guarded: after
     *  an audioserver death the objects are already invalid and may object to
     *  the farewell. */
    private fun releaseAllTracks() {
        val all = beepMono.asSequence() +
            sequenceOf(urgentMono, clearTrack, radarDroppedTrack, radarReconnectedTrack)
        all.forEach {
            try {
                it.release()
            } catch (_: Throwable) {}
        }
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
        urgentMono.setVolume(g)
        clearTrack.setVolume(g)
        radarDroppedTrack.setVolume(g)
        radarReconnectedTrack.setVolume(g)
    }

    private fun currentMonoGain(): Float {
        val linear = volumePct / 100f
        return linear * linear
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
        // close-approach beep.
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
         *  rebuild is cheap (7 small tracks from retained PCM). */
        internal const val REBUILD_MIN_INTERVAL_MS = 3_000L

        /** Single-pulse duration of the radar-reconnect cue, in ms. Doubles as
         *  the cue's total length for the abandon-timer (one tone, no gaps). */
        private const val RECONNECT_TONE_MS = 150

        /** Tail after the last cue's playback before audio focus is
         *  abandoned. Covers AudioTrack finish latency and gives media
         *  apps a clean restore window. */
        private const val ABANDON_SAFETY_MARGIN_MS = 50
    }
}
