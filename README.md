# Drive Music — Android / Kotlin Multiplatform

Android port of [Drive-Music-IOS](https://github.com/ChakkritGit/Drive-Music-IOS), structured so
the platform-independent half is shared rather than written twice.

## Status

Scaffold plus the first slice of shared logic. Builds and passes on every target:

| Task | Result |
| --- | --- |
| `:shared:testDebugUnitTest` | 59 tests, 0 failures |
| `:shared:iosSimulatorArm64Test` | 59 tests, 0 failures |
| `:androidApp:testDebugUnitTest` | 15 tests, 0 failures |
| `:shared:linkReleaseFrameworkIosArm64` | `DriveMusicShared.framework` produced |
| `:androidApp:assembleDebug` | APK produced |

The 59 shared tests run on both the Android and iOS targets from `commonTest`, which is the point
— the shared logic is verified on each platform that will actually run it, not just on one.

## Why the split is where it is

The iOS codebase is ~13,800 lines. Roughly:

| Layer | Lines (Swift) | Fate |
| --- | --- | --- |
| Core models, shuffle, recommendation model | 877 | shared — `:shared/commonMain` |
| Persistence | 561 | per-platform (SwiftData → Room), same interface |
| Networking / Sync | 280 | shareable via Ktor, or per-platform |
| Playback orchestration | 2,308 | logic shareable, audio calls per-platform |
| Audio engine | 2,552 | **rewrite** — no Android equivalent of `AVAudioEngine` |
| UI | 6,799 | **rewrite** — SwiftUI → Compose |

The audio engine is the one genuinely hard part. iOS builds a node graph with per-slot EQ, reverb
and time-pitch units and crossfades between two of them; Android has no framework equivalent
(`android.media.audiofx` attaches effects to an audio *session*, not per-node, and its quality is
device-dependent). Oboe/AAudio is not the answer either — it is for low-latency live audio and
would mean owning all codec and streaming plumbing for no benefit a music player can hear.

**This is now spiked rather than assumed** — see *The audio spike* below.

### One thing to get right early

Track analysis decodes a whole track into a float array — a ten-minute track is about 53 MB. On
iOS that is native memory; in Kotlin a `FloatArray` lives on the **Java heap**, which is capped
per app (commonly 128–512 MB). Analysis belongs in NDK code with its own FFT (PFFFT or KissFFT)
from the start, not ported to Kotlin and moved later. The iOS side hit a related problem — two
full decodes alive at once, ~160 MB — which is fixed there by analyzing while decoding; do the
same here rather than rediscovering it.

## What is shared so far

Everything in `:shared/commonMain` is pure logic with no platform API, and all of it is covered by
tests ported alongside it:

- `model/` — `DriveFile` (including the title-cleanup rules), `ParsedMetadata`, `LoopMode`,
  `TrackAnalysis`
- `recommendation/` — `Features`, `RecommendationModel`, `ListeningModel`
- `playback/Shuffle` — the windowed weighted shuffle, including the queue-splice re-indexing
- `analysis/MixPoints` — mix-in and mix-out detection
- `transition/` — `TransitionCurve`, `TransitionShape`, the four presets, and `TransitionPlan`:
  the whole of what a transition *is*, which is the highest-value thing in the codebase to have
  exactly one copy of

These carry across fixes made on the iOS side, deliberately, so the two do not drift:

- The feature hash takes its magnitude through `Long`. The hash wraps, so it can land on
  `Int.MIN_VALUE` — Swift's `abs` traps there and Kotlin's `absoluteValue` returns a value that is
  still negative, which then produces a negative array index.
- `RecommendationModel.hasExpectedShape` must gate any model loaded from disk. Without it, a
  build that changes the feature layout throws on the first prediction after upgrade, on every
  track change, unfixable by restarting.
- `Shuffle.reindexForInsertion` maps the existing order through a queue splice instead of clearing
  it. Clearing made queueing one track re-randomize all of Up Next.
- `MixPoints.mixOutPoint` is what stops a transition from having to wait for the last seconds of a
  track.
- `TransitionCurve` sorts its keyframes when decoding, not only when constructed. The iOS version
  uses its synthesized `Codable`, which reconstructs the array directly and skips the sort its own
  initializer performs — so a stored shape whose keyframes are not already in order decodes into a
  lane whose single forward scan reads the wrong segment for the whole transition.

`ParsedMetadata` deliberately does **not** carry cover-art bytes, unlike its Swift counterpart.
Inlining them there caused repeated memory problems — armed transitions, queue sheets and list
views all held megabytes of JPEG they only wanted a title from.

## Building

```
./gradlew :shared:testDebugUnitTest        # shared logic on the Android target
./gradlew :shared:iosSimulatorArm64Test    # the same tests on the iOS target
./gradlew :androidApp:assembleDebug
```

`local.properties` points at `~/Library/Android/sdk` (API 36.1, build-tools 36.1.0). Toolchain in
use: JDK 21 (JetBrains Runtime, from Android Studio), Gradle 8.13, AGP 8.9.1, Kotlin 2.1.21.

**The iOS targets need a full Xcode, not just Command Line Tools.** Kotlin/Native shells out to
`xcrun`, and with `xcode-select` pointing at `/Library/Developer/CommandLineTools` the link step
fails with a bare `xcrun ... exit code 72` that says nothing about the cause. Either point
`xcode-select` at Xcode, or set it per invocation:

```
DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer ./gradlew :shared:iosSimulatorArm64Test
```

The Android targets are unaffected.

## iOS consuming this module

`:shared` already declares `iosArm64`, `iosSimulatorArm64` and `iosX64` targets producing a
`DriveMusicShared` framework. They are declared now rather than added later on purpose — a target
added late is one whose `expect`/`actual` gaps are found late.

Nothing on the iOS side uses it yet. Switching over means deleting the corresponding Swift types
and importing the framework, and it is worth doing early: until then every fix in this layer has
to be made twice, which is exactly the cost this structure exists to avoid.

## The audio spike

`androidApp/audio` establishes that the transition is expressible on Media3. Two `ExoPlayer`
instances, each built with its own `DefaultAudioSink` carrying its own `TransitionAudioProcessor`
— that per-player hook is what makes this possible at all, since session-wide `audiofx` effects
cannot do different things to two tracks at the same moment.

The processor runs a per-channel biquad chain (high-pass, low-pass, bass shelf, then gain) whose
parameters come from `SlotAutomation`, which reads the lanes of a `TransitionShape` from
`:shared`. So "what a Mix sounds like" is defined once and both platforms apply the same numbers.

Verified by measurement rather than by inspection: the filter tests push sine waves through and
check the response, so a -24dB bass shelf is asserted to actually remove 24dB at 50Hz and leave
4kHz alone, and each sweep's corner is asserted to be -3dB. `SlotAutomationTest` pins the seam
between the shared curves and the engine.

**Not yet done, and none of it hidden:**

- **It has not been listened to.** For an audio path that is the only verification that finally
  counts. Everything below is known from reading the code, not from hearing it.
- Reverb is not applied. Media3 has no reverb processor, and `PresetReverb` attaches to a session
  so it would wash both slots. `SlotAutomation.reverbWet` returns the lane value so the gap stays
  visible; Rise leans on it heavily, Mix lightly and late.
- The beatmatch stretch uses plain `PlaybackParameters`, which moves pitch with speed. Media3's
  Sonic can hold pitch and should be used instead.
- `TransitionPlan.outgoingLoop` is ignored, so Rise's held bar does not happen.
- Sample format is 16-bit PCM only; float output is declined and Media3 converts.

## Not started

Persistence (Room), networking, Google auth, sync, the audio engine, and the entire UI. The audio
engine is the piece worth prototyping before committing to the rest — if a two-slot crossfade with
per-slot filtering does not work well on Media3, that changes the plan for everything downstream.
