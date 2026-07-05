# Audio design

This document explains the reasoning behind the app's alert audio: why the
cues are shaped the way they are, and which rules a contributor must not quietly
undo. The per-cue specifics live in the KDoc of `AlertBeeper.kt`,
`AlertDecider.kt`, and `RadarDropDecider.kt`, kept next to the code so they stay
in sync. This file carries the cross-cutting model that no single class holds:
what each cue is for, how the cues relate, and the perceptual constraints they
all answer to.

Audio is the primary interface. The rider's eyes are on the road, so the screen
overlay is secondary support; the sound is what keeps the rider aware and warns
of a threat. That framing drives every decision below.

## The listening environment

The design target is a rider in city traffic under a helmet, with wind noise at
speed, possibly with music or a podcast playing. Two consequences follow and
recur throughout:

- **Fine pitch is unusable.** Under that noise load a rider cannot reliably tell
  a rising two-note motif from a falling one, or resolve a carrier that differs
  by a few Hz. Information therefore lives in **count**, **timing**, and
  **timbre-class** (a whole band of sound), never in a melody or a fine pitch
  step. This is the rule the whole vocabulary is built on.
- **The low band is masked.** Traffic and wind put their energy low in the
  spectrum, so a low-pitched cue is swamped above roughly 25 km/h and only lands
  at a red light. Cues that must carry at speed sit clear of that mask.

## The three buckets

Every audible thing the app produces belongs to one of three buckets. The bucket
defines *delivery* (when it plays and how insistently), not timbre.

### 1. Awareness while riding

The 1-2-3 close-pass beeps. These paint the ambient traffic picture behind the
rider: something is near, nothing more. **They demand no action.** The rider
hears them often, and that is fine; they are a background sense of what is
around, like mirror glances made audible.

The beep tier (one, two, or three pulses) encodes the **distance band** of the
closest vehicle behind, and nothing else.

**Why tier must never encode speed or urgency.** This was tried and reverted. A
rider hears the near-tier beeps constantly, so the count channel's salience
saturates: the rider cannot hear the *absence* of the lower rungs, and a
three-beep cannot function as "pay attention" when three-beeps happen all the
time. Promoting a fast far-away closer to the top tier also makes the ambient
picture lie: it announces "someone is right behind" when the car is 25 m back.
Urgency has its own channel (bucket 2). Speed already lives in the escalation
**cadence**: a fast closer walks 1-2-3 in a couple of seconds, a slow one over
fifteen. Any future "make a fast closer more salient" work belongs in the timing
channel, never in relabeling the count. A design review that reasons about tier
salience as an absolute ladder ("higher tier = more warning") has missed this;
model the rider's contextual frequency of each cue instead.

Each tier also carries its own **rhythm** as a redundant fingerprint of the same
distance band: the two-pulse tier is a calm slow pair, the three-pulse tier a
tight triplet, so the rider tells them apart by rhythm as well as by count under
load. The rhythm is a second read on distance, not a speed code.

### 2. Urgent action

Attend now, in-ride. Two members, each with its own timbre-class so they are not
confused with each other or with the awareness beeps:

- **Imminent impact (`UrgentApproach`).** A collision-course geometry: a vehicle
  closing fast on an impact line while the rider is stationary or slow. It plays
  a four-pulse burst on a higher carrier that **looms** - each pulse louder than
  the last and the gaps accelerating - so the sound perceptually rushes at the
  rider. A looming-intensity envelope shortens brake reaction time (Gray,
  "Looming Auditory Collision Warnings for Driving", Human Factors 2011); the
  effect is carried by loudness and tempo, with no pitch motif. It is also
  panned toward the threat's side (see Stereo panning) to trim the head-check.
  It is told apart from a three-beep by count and cadence, not pitch.
- **Radar link dropped.** When the rear-radar link dies mid-ride the overlay
  freezes on its last frame, so a dead radar looks identical to a clear road.
  Silence would be a lie, so the loss is announced by sound: a status-class
  three-pulse. Because this one has a screen consequence the rider might act on
  (reconnect the radar), it also gets a screen banner; a persistence option
  exists for riders whose only rear sensor is the radar.

### 3. End of trip

Anything that can wait. The in-ride audio channel is reserved for live threats,
so non-safety equipment state is never spoken while riding; it surfaces on an
attention surface after the ride instead. The canonical example is the radar's
battery being dangerously low **when the radar did not actually die during the
ride**: the rider can charge it tonight, so a mid-ride beep would only add noise
to the awareness channel and train the rider to tune the channel out. (If the
radar actually dies, that is a bucket-2 event and the drop cue covers it.)

## Encoding rules

The status timbre-class (radar drop and reconnect) sits
on a single mid carrier around 900 Hz. That band was chosen deliberately: an
earlier, lower status band sat inside the traffic and wind mask, so "your radar
dropped" was inaudible above about 25 km/h. Lifting the whole class into the
roughly 800-1000 Hz window - still a full class below the sharp threat beeps -
makes the status cues carry at speed. This is a timbre-class move (a band shift),
not a fine-pitch distinction; within the class the cues are separated by
**count**:

| Cue | Bucket | Carrier | Shape |
|-----|--------|---------|-------|
| Close-pass beep, tier 1/2/3 | Awareness | 3200 Hz | 1 / 2 / 3 sharp pulses, per-tier rhythm |
| Imminent impact (`UrgentApproach`) | Urgent | 3800 Hz | 4 looming pulses (rising loudness, accelerating gaps) |
| Radar dropped | Urgent | ~900 Hz | 3 pulses |
| Radar reconnected | End of drop episode | ~900 Hz | 1 pulse |
| All clear | (road empties) | 1100 to 700 Hz | soft two-tone descent |

The status cues share one carrier and are read purely by pulse count: one for
reconnect, two for battery, three for drop. The all-clear is its own class (a
falling glide, never a status carrier), and it is never allowed to overlap a beep
on the speaker.

## Parsimony

Fewer, more meaningful cues beat a stream of them. A rider who hears a cacophony
of beeps stops hearing any of them ("my brain just tunes out"), which is the
classic alarm-fatigue failure and the opposite of a safety feature. The awareness
channel is therefore managed with **episode semantics** rather than a beep per
radar frame:

- A beep describes the **closest** threat only. A new vehicle entering the close
  set is silent unless it raises the closest-urgency tier; piling a beep on a
  farther car while a closer one is already being announced adds noise, not
  information.
- **Re-trigger suppression.** Once the app has beeped for a given track at a
  given tier it does not beep again for that same track at that same tier.
  Distance jitter that flaps a car across a tier boundary does not re-fire, and
  an overtaking car handing off to the remaining traffic only re-beeps if what
  remains is strictly closer than the peak the departing car reached.
- A short cooldown collapses a burst of triggers into one beep at the closest
  urgency, so a multi-vehicle scene does not machine-gun the rider. The one
  exception is a genuine tier *raise* (the closest car got closer), which is real
  new information and fires immediately rather than being swallowed by the
  cooldown window.

Parsimony has one hard exception, the **false-clear asymmetry**. A *false*
all-clear - telling the rider the road is clear when a car is still behind - is
the cardinal sin: it actively invites the rider to move into danger. A *delayed*
or absent all-clear is merely mild: the rider simply has not yet been told the
road cleared, and no harm follows. The two errors are not balanced, so the
all-clear is tuned conservatively toward silence. Concretely, the all-clear is
gated on whether any vehicle is *physically* behind in range (the raw radar read,
including a matched-speed follower or one that briefly dropped and returned under
a new identity), not on the managed beep-path set, and it must hold for a grace
period before it chimes. A car that genuinely leaves clears after the grace; a
follower sitting in the blind spot never produces a "road clear."

The urgent-impact override runs on its own path, independent of the beep
cooldown, but it has its own parsimony: **episode pacing**. Urgent sightings
close together in time belong to one episode, and within an episode the cue
fires once and then repeats at a steady pace rather than once per car - a
platoon of successive impact-line vehicles is one sustained situation, not
five separate emergencies, and machine-gunning the strongest cue in the
vocabulary would burn its meaning fastest of all. A markedly faster new
closer bypasses the pacing (that is genuinely new information), and a lapse
in urgent sightings ends the episode so the next threat fires immediately.

## When the audio steps back

Three states quiet or reshape the audio, by design:

- **Media ducking.** Each cue requests transient audio focus so a podcast or
  music ducks for the cue and restores afterward. Back-to-back cues hold the duck
  across the burst rather than ducking and un-ducking between pulses.
- **Media-volume floor.** Independently of the app's own volume setting, every
  cue briefly lifts the system alarm stream to a floor a few steps above the
  rider's current *media* volume, without ever turning the alarm stream *down*
  below the rider's own preset. A rider listening to a loud podcast but with a
  quiet alarm slider would otherwise hear a safety alert too faintly. The rider's
  own media level is used as a proxy for how loud the environment is; there is no
  microphone and no privacy cost. It is best-effort (some devices reject volume
  writes from a background service, which is fine, the app-level gain still
  plays) and burst-scoped (lifted once, restored once, and repaired at next start
  if a process death leaks the lift).
- **In-call suppression.** While a phone or VoIP call is active the cue's audio
  path is skipped entirely; the visual overlay still fires. This is
  non-negotiable and has no settings toggle. Alarm-usage audio behaves
  unpredictably across devices while a call routes the output, and preserving
  call audio integrity outranks a beep the rider would struggle to act on
  mid-call anyway.

Audio is the primary channel, so `AlertBeeper` must never fail silently. A cue
that could not sound is reported through the same hook as one that did, with a
`cue_failed` marker, so the sounded-alert tally never counts a silent cue as
heard. A dead audio server (a rare but real mid-ride event) is detected by the
play failing, which triggers a throttled rebuild of the cue tracks and a retry,
so the cue still sounds once the server is back.

## Stereo panning (experimental, off by default)

When enabled, the close-pass beep and the urgent cue bias toward the threat's
side to give a pre-attentive "which side" cue that saves a head-check. The pan is
hard (full deflection mutes the opposite channel), which is safe because the
sound always comes from the phone's two built-in speakers, both mounted on the
bike, so there is no silent-ear risk. On headphone-class routes the channel
labels travel intact; on the built-in speaker it only helps in landscape (the two
speakers are far enough apart there) and the app compensates for phone rotation
so the cue reaches the correct ear. Portrait and unknown routes fall back to
mono. It is default-off because its value depends on assumptions about
localisation that need on-road validation before it becomes a default.

## The alarm-system frame, and the non-claim

The model above - distinct alarm *classes* by timbre rather than fine pitch,
alarm parsimony, and an override that cuts through a paused or managed channel
when a new condition appears - is an informal implementation of the IEC 60601-1-8
medical-alarm pattern. It is design inspiration only; the app is not a medical
device and makes no compliance claim.

## How this relates to the code and tests

- The **KDoc is authoritative for each cue.** `AlertBeeper.kt` describes the
  exact shape, carrier, and rationale of every tone; `AlertDecider.kt` describes
  when a beep, an all-clear, or the urgent override fires; `RadarDropDecider.kt`
  describes the drop and reconnect cues and the gate that keeps the drop cue from
  firing at a normal dismount. This document is the cross-cutting model; where it
  and the KDoc ever drift, the KDoc is correct about behaviour.
- **Decisions are pinned by tests**, so a change that quietly reverts one shows
  up as a failing, named test. A few load-bearing examples (do not rename or
  "simplify" these):
  - `cacophony scenario truck pass produces at most three audible beeps` - the
    parsimony ceiling for a single overtake.
  - `same-tier new entry while closer track still close is silent` and
    `overtake re-ack at same-or-lower tier is silent` - the closest-only model.
  - `intra-tier distance flap does NOT re-fire` - the tier latch.
  - `platoon of new tids inside one episode fires once not per car` and
    `markedly faster closer bypasses episode pacing` - the urgent channel's
    episode pacing and its escape hatch.
  - `AlertBeeperCueShapeTest` pins each cue's pulse count and inter-pulse
    timing, so the count-and-timing vocabulary cannot silently change; the pan
    resolution is exhausted in `AlertBeeperPanTest`.

Before changing alert behaviour, read the relevant KDoc, run the decider tests,
and replay the cue-ledger gate (`CueLedgerReplayTest`) so any change in cue
parsimony surfaces as a reviewable diff.
