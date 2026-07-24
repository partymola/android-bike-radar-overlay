// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executor
import kotlin.math.abs

/** Float multiplication (e.g. 0.7f * 1.0f) is imprecise; data-class
 *  equality on the gain pair fails. Compare component-wise with delta. */
private fun assertStereo(
    expectedLeft: Float,
    expectedRight: Float,
    actual: AlertBeeper.PanResult,
    msg: String = "",
    delta: Float = 0.0001f,
) {
    assertTrue("$msg: expected Stereo result, got $actual", actual is AlertBeeper.PanResult.Stereo)
    val stereo = actual as AlertBeeper.PanResult.Stereo
    assertEquals("$msg: left", expectedLeft, stereo.left, delta)
    assertEquals("$msg: right", expectedRight, stereo.right, delta)
}

private fun assertMono(
    expectedGain: Float,
    actual: AlertBeeper.PanResult,
    msg: String = "",
    delta: Float = 0.0001f,
) {
    assertTrue("$msg: expected Mono result, got $actual", actual is AlertBeeper.PanResult.Mono)
    assertEquals("$msg: gain", expectedGain, (actual as AlertBeeper.PanResult.Mono).gain, delta)
}

/**
 * Exhaustive coverage of the pan-decision matrix:
 *   {pan on / off}
 * x {headphone, built-in speaker, unknown route}
 * x {rotation 0 / 90 / 180 / 270}
 * x {invert off / on}
 *
 * Wrong-ear directional cues are a safety hazard, so these tests pin
 * every load-bearing combination. The pure `resolvePan` function lets
 * us assert exact gains without AudioTrack mocking.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperPanTest {

    private fun beeper(): AlertBeeper {
        val ctx = RuntimeEnvironment.getApplication()
        val am = ctx.getSystemService(AudioManager::class.java)
        return AlertBeeper(am)
    }

    private val floor = AlertBeeper.FAR_CHANNEL_FLOOR

    /** A beeper with panning on and the played track captured, for the tests
     *  that pin which pre-built track a cue reaches. */
    private fun panningBeeper(onPlayed: (AudioTrack) -> Unit): AlertBeeper {
        val b = AlertBeeper(
            RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java),
            executor = Executor { it.run() },
            playTrackOverride = {
                onPlayed(it)
                true
            },
        )
        b.setPanning(enabled = true, invertLR = false)
        return b
    }

    /** Throwaway stereo track for the factory seam - the test reads the PCM
     *  it was handed, never the track. */
    private fun silentStereoTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(44100)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(4)
        .build()

    // ── Pan formula (pure shape, route-independent) ──────────────────────

    @Test fun centreIsBothChannelsFull() {
        val (l, r) = beeper().computePan(0f)
        assertEquals(1.0f, l, 0.0001f)
        assertEquals(1.0f, r, 0.0001f)
    }

    @Test fun fullLeftFloorsRightRatherThanMutingIt() {
        // A muted channel means a single-earbud rider hears nothing at all
        // for threats on the missing side, while still hearing every centred
        // all-clear. The floor keeps the cue present in both ears.
        val (l, r) = beeper().computePan(-1f)
        assertEquals(1.0f, l, 0.0001f)
        assertEquals(floor, r, 0.0001f)
    }

    @Test fun fullRightFloorsLeftRatherThanMutingIt() {
        val (l, r) = beeper().computePan(+1f)
        assertEquals(floor, l, 0.0001f)
        assertEquals(1.0f, r, 0.0001f)
    }

    @Test fun noResolvedPanChannelIsEverSilent() {
        // The load-bearing invariant, swept across every route and lateral
        // position: whatever resolvePan returns, both channels carry signal.
        val b = beeper()
        var stereoCases = 0
        for (monoGain in listOf(1.0f, 0.5f)) {
            var lat = -1.2f
            while (lat <= 1.2f) {
                for (headphones in listOf(true, false)) {
                    for (rotation in listOf(
                        Surface.ROTATION_0,
                        Surface.ROTATION_90,
                        Surface.ROTATION_180,
                        Surface.ROTATION_270,
                    )) {
                        for (invert in listOf(true, false)) {
                            val res = b.resolvePan(
                                lateralPos = lat,
                                monoGain = monoGain,
                                panningEnabled = true,
                                invertLR = invert,
                                hasHeadphoneRoute = headphones,
                                builtinSpeakerActive = !headphones,
                                rotation = rotation,
                            )
                            if (res is AlertBeeper.PanResult.Stereo) {
                                stereoCases++
                                val minChannel = floor * monoGain
                                assertTrue(
                                    "gain=$monoGain lat=$lat headphones=$headphones rot=$rotation inv=$invert " +
                                        "left ${res.left} below floor",
                                    res.left >= minChannel - 0.0001f,
                                )
                                assertTrue(
                                    "gain=$monoGain lat=$lat headphones=$headphones rot=$rotation inv=$invert " +
                                        "right ${res.right} below floor",
                                    res.right >= minChannel - 0.0001f,
                                )
                            }
                        }
                    }
                }
                lat += 0.1f
            }
        }
        // Without this the sweep passes with zero assertions executed if a
        // regression makes resolvePan return Mono everywhere.
        assertTrue("the sweep must actually reach the stereo path", stereoCases > 0)
    }

    @Test fun halfLeftHalvesTheFarChannel() {
        val (l, r) = beeper().computePan(-0.5f)
        assertEquals(1.0f, l, 0.0001f)
        assertEquals(0.5f, r, 0.0001f)
    }

    @Test fun halfRightHalvesTheFarChannel() {
        val (l, r) = beeper().computePan(+0.5f)
        assertEquals(0.5f, l, 0.0001f)
        assertEquals(1.0f, r, 0.0001f)
    }

    @Test fun outOfRangeClampsToFullDeflection() {
        val left = beeper().computePan(-5f)
        assertEquals(1.0f, left.first, 0.0001f)
        assertEquals(floor, left.second, 0.0001f)
        val right = beeper().computePan(+5f)
        assertEquals(floor, right.first, 0.0001f)
        assertEquals(1.0f, right.second, 0.0001f)
    }

    // ── resolvePan: panning disabled ─────────────────────────────────────

    @Test fun panningOffAlwaysReturnsMonoRegardlessOfRouteRotationLateral() {
        // The disabled state is unconditional - no route, rotation, invert,
        // or lateralPos value should produce stereo. Cross-check the matrix.
        val b = beeper()
        for (lat in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
                for (headphones in listOf(true, false)) {
                    for (speaker in listOf(true, false)) {
                        for (invert in listOf(true, false)) {
                            val r = b.resolvePan(lat, 1f, false, invert, headphones, speaker, rot)
                            assertMono(1f, r, "off must be mono for lat=$lat rot=$rot hp=$headphones spk=$speaker inv=$invert")
                        }
                    }
                }
            }
        }
    }

    // ── resolvePan: headphone route ──────────────────────────────────────

    @Test fun headphoneRouteFullLeftLandsLouderOnLeftChannel() {
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            val r = b.resolvePan(-1f, 1f, true, false, true, false, rot)
            assertStereo(1.0f, floor, r, "headphone+lat=-1+rot=$rot")
        }
    }

    @Test fun headphoneRouteFullRightLandsLouderOnRightChannel() {
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            val r = b.resolvePan(+1f, 1f, true, false, true, false, rot)
            assertStereo(floor, 1.0f, r, "headphone+lat=+1+rot=$rot")
        }
    }

    @Test fun headphoneRouteCentreIsBalanced() {
        assertStereo(1.0f, 1.0f, beeper().resolvePan(0f, 1f, true, false, true, false, Surface.ROTATION_90))
    }

    @Test fun headphoneRouteInvertSwapsLR() {
        assertStereo(
            floor,
            1.0f,
            beeper().resolvePan(-1f, 1f, true, invertLR = true, hasHeadphoneRoute = true, builtinSpeakerActive = false, rotation = Surface.ROTATION_90),
            "headphone+invert: bike-LEFT lands louder on RIGHT channel",
        )
    }

    // ── resolvePan: built-in speaker route ───────────────────────────────

    @Test fun speakerRotation90PansLikeHeadphone() {
        // ROTATION_90 (USB-right landscape): phone's earpiece is on the
        // rider's left. HAL maps audio L to earpiece (always). So audio L
        // already reaches the rider's left ear; no swap needed.
        val b = beeper()
        assertStereo(1.0f, floor, b.resolvePan(-1f, 1f, true, false, false, true, Surface.ROTATION_90))
        assertStereo(floor, 1.0f, b.resolvePan(+1f, 1f, true, false, false, true, Surface.ROTATION_90))
    }

    @Test fun speakerRotation270SwapsToCompensateForHALMapping() {
        // ROTATION_270 (USB-left landscape): phone flipped 180 in landscape.
        // Earpiece (where HAL routes audio L) is now on the rider's RIGHT.
        // To reach the rider's left ear, LOUDER gain must go on audio R -
        // which Android routes to the bottom-main speaker, now on the
        // rider's left.
        val b = beeper()
        assertStereo(
            floor,
            1.0f,
            b.resolvePan(-1f, 1f, true, false, false, true, Surface.ROTATION_270),
            "speaker+rot=270: bike-LEFT must put LOUDER gain on R channel",
        )
        assertStereo(
            1.0f,
            floor,
            b.resolvePan(+1f, 1f, true, false, false, true, Surface.ROTATION_270),
            "speaker+rot=270: bike-RIGHT must put LOUDER gain on L channel",
        )
    }

    @Test fun speakerPortraitFallsBackToMono() {
        // Portrait: speakers are physically close (bottom edge); no usable
        // lateralisation. Mono.
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_180)) {
            for (lat in listOf(-1f, 0f, 1f)) {
                assertMono(
                    1f,
                    b.resolvePan(lat, 1f, true, false, false, true, rot),
                    "speaker+portrait rot=$rot lat=$lat must be mono",
                )
            }
        }
    }

    @Test fun speakerInvertCancelsRotation270Swap() {
        // XOR: rotation-270 swaps AND invertLR swaps; both fire = no net
        // swap. Matches no-swap-no-invert (rotation 90 headphone-equivalent).
        assertStereo(
            1.0f,
            floor,
            beeper().resolvePan(-1f, 1f, true, invertLR = true, hasHeadphoneRoute = false, builtinSpeakerActive = true, rotation = Surface.ROTATION_270),
        )
    }

    @Test fun speakerRotation90InvertSwapsChannels() {
        // ROTATION_90 doesn't swap; invertLR alone produces the swapped result.
        assertStereo(
            floor,
            1.0f,
            beeper().resolvePan(-1f, 1f, true, invertLR = true, hasHeadphoneRoute = false, builtinSpeakerActive = true, rotation = Surface.ROTATION_90),
        )
    }

    // ── resolvePan: unknown / unsupported route ──────────────────────────

    @Test fun unknownRouteFallsBackToMono() {
        // Neither headphone nor built-in speaker. Examples: BT car bus
        // (TYPE_BUS), portable BLE speaker, casting target. Mono is the
        // safe fallback (spatial layout unknown).
        val b = beeper()
        for (rot in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            assertMono(
                1f,
                b.resolvePan(-1f, 1f, true, false, false, false, rot),
                "unknown route rot=$rot must be mono",
            )
        }
    }

    // ── Volume scaling ───────────────────────────────────────────────────

    @Test fun monoGainScalesStereoGains() {
        // resolvePan multiplies the pan-formula output by monoGain so user
        // volume cuts both channels proportionally. Half volume (0.5) on
        // full-left pan -> (0.5, floor * 0.5). The floor scales with volume
        // like everything else; it is a pan ratio, not an absolute level.
        assertStereo(
            0.5f,
            floor * 0.5f,
            beeper().resolvePan(-1f, 0.5f, true, false, true, false, Surface.ROTATION_90),
        )
    }

    @Test fun monoGainScalesMonoResult() {
        assertMono(0.25f, beeper().resolvePan(-1f, 0.25f, false, false, true, false, Surface.ROTATION_90))
    }

    // ── nearestPanBucket: resolved gains -> pre-built stereo bucket ───────
    // These are written formula-relative (driven by resolvePan / bucketScales
    // rather than magic gain numbers) so they survive pan-width tuning.

    @Test fun farChannelFloorIsAboveZeroAndNotVanishing() {
        // The one assertion in this file that does NOT read the constant as
        // its own oracle. Every other floor test compares against
        // FAR_CHANNEL_FLOOR, so setting it to 0f turns the whole suite green
        // while silently restoring the muting this floor exists to remove:
        // for lateralPos in [-1,1], coerceIn(0f, 1f) is arithmetically the
        // old coerceAtMost(1f). The magnitude is a tuning choice; being
        // above zero is the contract the class KDoc guarantees.
        assertTrue(
            "the far channel must never be silenced (floor=$floor)",
            floor > 0f,
        )
        assertTrue(
            "a floor this low is silence in all but name (floor=$floor)",
            floor >= 0.1f,
        )
    }

    @Test fun fullLeftCuePlaysTheLeftExtremeBucketTrack() {
        // The last hop the pure-formula tests stop short of: resolvePan ->
        // nearestPanBucket -> row index -> the track that actually plays.
        // Robolectric cannot read a track's PCM, but it can compare identity,
        // so a mirrored or offset row LOOKUP is catchable here. (The row's own
        // build order is pinned separately by bucketRowSlotsCarryTheirOwnBucketPcm
        // - identity alone cannot see a reversed row, since row[0] is still
        // the object played.)
        var played: AudioTrack? = null
        val b = panningBeeper { played = it }
        val row = b.beepBucketRow(0)
        b.play(beeps = 1, lateralPos = -1f)
        assertSame("a full-left cue must play the left-extreme bucket track", row[0], played)
    }

    @Test fun eachBeepCountPlaysItsOwnCueRow() {
        // play() looks the row up by cue index, so a hardcoded or offset idx
        // sends every panned cue to one tier's row. This pins the LOOKUP;
        // which PCM each row was baked from is pinned by
        // panRowsAreBakedFromTheirOwnCuePcm. Nothing else in the suite sees
        // either, because the reported "beep count=N" string comes from the
        // decision path, not from the track that played.
        var played: AudioTrack? = null
        val b = panningBeeper { played = it }
        for (beeps in 1..3) {
            val row = b.beepBucketRow(beeps - 1)
            b.play(beeps = beeps, lateralPos = -1f)
            assertSame("a $beeps-beep cue must play the $beeps-beep row", row[0], played)
        }
    }

    @Test fun urgentCuePlaysItsOwnRowNotTheBeepRow() {
        // Wiring the urgent cue to a beep row would ship the action-channel
        // warning with the awareness timbre - an alarm-class swap, and the
        // cue tally would still read "urgent". Full RIGHT here, so a constant
        // bucket index fails this or the full-left test above.
        var played: AudioTrack? = null
        val b = panningBeeper { played = it }
        val urgentRow = b.urgentBucketRow()
        b.playUrgent(lateralPos = +1f)
        assertSame("a full-right urgent cue must play the urgent row's right extreme", urgentRow[4], played)
    }

    @Test fun bucketRowSlotsCarryTheirOwnBucketPcm() {
        // buildBucketRow is the last index hop and the only one no other test
        // can see: reverse its subscript and EVERY panned cue goes to the
        // wrong ear while the row still has the right length and the lookup
        // still picks slot 0. Substituting the track factory is the only way
        // to read back which PCM each slot was actually filled with.
        val captured = mutableListOf<ShortArray>()
        val b = AlertBeeper(
            RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java),
            executor = Executor { it.run() },
            stereoTrackFactory = { pcm ->
                captured += pcm
                silentStereoTrack()
            },
        )
        b.beepBucketRow(0)
        assertEquals("one track built per bucket", 5, captured.size)

        // Centre bucket is unattenuated on both channels, so it hands us the
        // source mono samples without reaching into the cue's PCM. Anchor on
        // the loudest frame: a tone starts at zero, and comparing channels
        // there would make every assertion below vacuously true.
        val scales = b.bucketScales
        val centre = captured[2]
        val peakFrame = (0 until centre.size / 2).maxByOrNull { abs(centre[2 * it].toInt()) }!!
        val left = 2 * peakFrame
        val right = left + 1
        val mono = centre[left].toInt()
        assertTrue("the cue must have a non-silent frame to compare", abs(mono) > 0)
        assertEquals("centre bucket is balanced", centre[left], centre[right])
        for (bucket in 0 until 5) {
            val (l, r) = scales[bucket]
            assertEquals(
                "slot $bucket must carry bucket $bucket's left scale",
                (mono * l).toInt().toShort(),
                captured[bucket][left],
            )
            assertEquals(
                "slot $bucket must carry bucket $bucket's right scale",
                (mono * r).toInt().toShort(),
                captured[bucket][right],
            )
        }
        // Magnitude, not signed value: the anchor frame may sit on a negative
        // half-cycle, where the louder channel is the more negative one.
        assertTrue(
            "slot 0 must be the left extreme",
            abs(captured[0][left].toInt()) > abs(captured[0][right].toInt()),
        )
        assertTrue(
            "slot 4 must be the right extreme",
            abs(captured[4][right].toInt()) > abs(captured[4][left].toInt()),
        )
    }

    @Test fun panRowsAreBakedFromTheirOwnCuePcm() {
        // The row-identity tests above pin which row a cue LOOKS UP; this pins
        // which cue buffer each row was BAKED FROM. Baking the urgent row from
        // beepPcm ships every panned urgent warning in the awareness timbre -
        // an alarm-class swap that leaves the row objects, the lookup and the
        // reported cue tag all correct. Assert the exact source size (not just
        // that the three differ), so a same-length swap or a tuning change that
        // collides two lengths cannot slip through.
        val captured = mutableMapOf<String, Int>()
        var label = ""
        val b = AlertBeeper(
            RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java),
            executor = Executor { it.run() },
            stereoTrackFactory = { pcm ->
                captured[label] = pcm.size
                silentStereoTrack()
            },
        )
        label = "beep1"
        b.beepBucketRow(0)
        label = "beep3"
        b.beepBucketRow(2)
        label = "urgent"
        b.urgentBucketRow()

        // Stereo PCM is twice the mono cue buffer (L+R interleaved).
        assertEquals("beep row 0 must bake the 1-beep cue", b.buildBeepPcm(1).size * 2, captured["beep1"])
        assertEquals("beep row 2 must bake the 3-beep cue", b.buildBeepPcm(3).size * 2, captured["beep3"])
        assertEquals("the urgent row must bake the urgent cue", b.buildUrgentPcm().size * 2, captured["urgent"])
    }

    @Test fun panPlaybackBindsTrackToItsLevel() {
        // The seam playPanned consumes: it decides BOTH which track plays and
        // at what volume, and this is the only place the rider's volume reaches
        // a panned cue (applyVolume's pre-set is overwritten on every play).
        // Pin the binding, not just the arithmetic - level = 1.0f (slider
        // ignored) or the quieter channel (every cue 12 dB down) both die here.
        val b = beeper()
        val row = b.beepBucketRow(0)
        val mono = row[0] // stand-in mono track; unused on the stereo branch

        val left = b.resolvePan(-1f, 0.5f, true, false, true, false, Surface.ROTATION_90)
            as AlertBeeper.PanResult.Stereo
        val (leftTrack, leftLevel) = b.resolvePlayback(left, mono) { row }
        assertSame("full-left plays the left-extreme bucket", row[0], leftTrack)
        assertEquals("at the louder resolved channel, not the floor", 0.5f, leftLevel, 0.0001f)
        assertTrue("the level must not be the floored channel", leftLevel > left.right)

        // Mirror: a right-side threat must not silently play 12 dB quiet.
        val right = b.resolvePan(+1f, 0.5f, true, false, true, false, Surface.ROTATION_90)
            as AlertBeeper.PanResult.Stereo
        val (rightTrack, rightLevel) = b.resolvePlayback(right, mono) { row }
        assertSame("full-right plays the right-extreme bucket", row[4], rightTrack)
        assertEquals("same louder-channel level on the other side", 0.5f, rightLevel, 0.0001f)
        assertTrue("the level must not be the floored channel", rightLevel > right.left)

        // Mono result plays the mono track at its own gain, untouched.
        val monoResult = b.resolvePan(0f, 0.25f, false, false, true, false, Surface.ROTATION_90)
            as AlertBeeper.PanResult.Mono
        val (monoTrack, monoLevel) = b.resolvePlayback(monoResult, mono) { row }
        assertSame("mono result plays the mono track", mono, monoTrack)
        assertEquals("at its own gain", 0.25f, monoLevel, 0.0001f)
    }

    @Test fun nearestPanBucket_roundTripsResolvePanGains() {
        // The load-bearing property: each of the 5 bucket lateral positions,
        // run through resolvePan, maps back to its own bucket index - so the
        // played track reproduces the resolved L/R balance.
        val b = beeper()
        listOf(-1f, -0.5f, 0f, 0.5f, 1f).forEachIndexed { idx, lat ->
            val r = b.resolvePan(lat, 1f, true, false, true, false, Surface.ROTATION_90)
                as AlertBeeper.PanResult.Stereo
            assertEquals("lat=$lat should map to bucket $idx", idx, b.nearestPanBucket(r.left, r.right))
        }
    }

    @Test fun nearestPanBucket_centreGuardsZeroPeak() {
        // Silent (both channels 0) can't divide by peak; fall back to centre.
        assertEquals(2, beeper().nearestPanBucket(0f, 0f))
    }

    @Test fun nearestPanBucket_isVolumeIndependent() {
        // Full-left at full vs half volume must select the same (left-extreme)
        // bucket - the imbalance is normalised by the louder channel.
        val b = beeper()
        val full = b.resolvePan(-1f, 1f, true, false, true, false, Surface.ROTATION_90)
            as AlertBeeper.PanResult.Stereo
        val half = b.resolvePan(-1f, 0.5f, true, false, true, false, Surface.ROTATION_90)
            as AlertBeeper.PanResult.Stereo
        assertEquals(0, b.nearestPanBucket(full.left, full.right))
        assertEquals(
            "half volume must pick the same bucket as full",
            b.nearestPanBucket(full.left, full.right),
            b.nearestPanBucket(half.left, half.right),
        )
    }

    @Test fun bucketScales_directionLowerIndexLouderLeft() {
        // Pins the *direction* of the baked ratio, not just the magnitude
        // nearestPanBucket sees. bucketRowPcm carries this into the samples;
        // a transposed scale pair there fails here first.
        val s = beeper().bucketScales
        assertTrue("bucket 0 must be louder-left", s[0].first > s[0].second)
        assertEquals("centre balanced", s[2].first, s[2].second, 0.0001f)
        assertTrue("bucket 4 must be louder-right", s[4].second > s[4].first)
        assertEquals("mirror: bucket 0 left == bucket 4 right", s[0].first, s[4].second, 0.0001f)
        assertEquals("mirror: bucket 0 right == bucket 4 left", s[0].second, s[4].first, 0.0001f)
    }

    @Test fun bucketRowPcm_putsEachBucketScaleOnTheCorrectChannel() {
        // The wiring between the baked scales and the track, for every bucket.
        // A swapped pair here sends one cue to the wrong ear, which is the
        // hazard the pan feature exists to avoid, and it is the last point
        // before the AudioTrack where the samples are still readable.
        val b = beeper()
        val sample: Short = 4000
        for (bucket in 0 until 5) {
            val (l, r) = b.bucketScales[bucket]
            val pcm = b.bucketRowPcm(shortArrayOf(sample), bucket)
            assertEquals("bucket $bucket left sample", (sample * l).toInt().toShort(), pcm[0])
            assertEquals("bucket $bucket right sample", (sample * r).toInt().toShort(), pcm[1])
        }
        // And concretely at the extremes: bucket 0 is louder-left, 4 louder-right.
        val left = b.bucketRowPcm(shortArrayOf(sample), 0)
        assertTrue("bucket 0 must be louder on the left, got ${left.toList()}", left[0] > left[1])
        val right = b.bucketRowPcm(shortArrayOf(sample), 4)
        assertTrue("bucket 4 must be louder on the right, got ${right.toList()}", right[1] > right[0])
    }

    @Test fun bucketScales_extremesAreBakedAtTheFloorNotBelowIt() {
        // The baked tracks are what the rider actually hears; the direction
        // and mirror checks above both survive a far channel baked too quiet,
        // and nearestPanBucket only compares imbalances, so a bake that
        // weakened the floor without zeroing it would select the right bucket
        // and still play a channel quieter than intended. Pin the magnitude.
        // Reads FAR_CHANNEL_FLOOR as its own oracle, so it cannot see a
        // mutation of that constant's declaration - only a bake that diverges
        // from it. That is the contract worth pinning; the constant's value is
        // a deliberate choice, not an invariant.
        val s = beeper().bucketScales
        assertEquals("bucket 0 far channel must sit exactly on the floor", floor, s[0].second, 0.0001f)
        assertEquals("bucket 4 far channel must sit exactly on the floor", floor, s[4].first, 0.0001f)
        assertEquals("bucket 0 near channel is unattenuated", 1.0f, s[0].first, 0.0001f)
        assertEquals("bucket 4 near channel is unattenuated", 1.0f, s[4].second, 0.0001f)
    }

    @Test fun nearestPanBucket_invertMirrorsBucket() {
        // Invert is folded into resolvePan's gains; a bike-left cue with
        // invert must select the right-extreme bucket (wrong-ear guard).
        val b = beeper()
        val r = b.resolvePan(
            -1f,
            1f,
            true,
            invertLR = true,
            hasHeadphoneRoute = true,
            builtinSpeakerActive = false,
            rotation = Surface.ROTATION_90,
        ) as AlertBeeper.PanResult.Stereo
        assertEquals("bike-left + invert -> right-extreme bucket", 4, b.nearestPanBucket(r.left, r.right))
    }

    @Test fun interleaveStereo_putsLeftFirstAndScalesEachChannel() {
        // Pins the interleave: out[2i] = left, out[2i+1] = right, each scaled.
        // Catches an L/R swap or a transposed scale argument in the bake.
        val out = beeper().interleaveStereo(shortArrayOf(1000, -2000, 3000), 1.0f, 0.5f)
        assertEquals(6, out.size)
        assertEquals(1000, out[0].toInt())
        assertEquals(500, out[1].toInt())
        assertEquals(-2000, out[2].toInt())
        assertEquals(-1000, out[3].toInt())
        assertEquals(3000, out[4].toInt())
        assertEquals(1500, out[5].toInt())
    }

    @Test fun interleaveStereo_atFullDeflectionStillCarriesTheFarChannel() {
        // The baked-track guard: the floor has to survive into the PCM, or
        // the pre-built extreme bucket is silent in one channel regardless
        // of what computePan returned.
        // Assert the MAGNITUDE, not merely non-zero: this function is the
        // ground truth for what the rider hears (Robolectric cannot read an
        // AudioTrack's PCM), so a bake that carried the far channel through at
        // a fraction of the floor would still be "not silent" and would ship.
        val b = beeper()
        val (l, r) = b.computePan(-1f)
        val sample: Short = 4000
        val out = b.interleaveStereo(shortArrayOf(sample), l, r)
        assertEquals("near channel unattenuated", sample, out[0])
        assertEquals("far channel baked at the floor", (sample * floor).toInt().toShort(), out[1])
    }

    @Test fun interleaveStereo_scalingToZeroStillMutesThatChannel() {
        // The primitive itself still mutes on a 0 scale; no bake asks it to
        // any more, but the pan formula is the only thing keeping that true.
        val out = beeper().interleaveStereo(shortArrayOf(1000), 1.0f, 0.0f)
        assertEquals(1000, out[0].toInt())
        assertEquals(0, out[1].toInt())
    }

    @Test fun nearestPanBucket_speakerRotation270SelectsSwappedBucket() {
        // Rotation-270 swap is folded into the gains; a bike-left cue on the
        // speaker at 270 must select the right-channel bucket (wrong-ear guard).
        val b = beeper()
        val r = b.resolvePan(
            -1f,
            1f,
            true,
            false,
            hasHeadphoneRoute = false,
            builtinSpeakerActive = true,
            rotation = Surface.ROTATION_270,
        ) as AlertBeeper.PanResult.Stereo
        assertEquals("bike-left on speaker@270 -> right-channel bucket", 4, b.nearestPanBucket(r.left, r.right))
    }
}
