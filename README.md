# Drive Music — Android / Kotlin Multiplatform

Android port of [Drive-Music-IOS](https://github.com/ChakkritGit/Drive-Music-IOS), structured so
the platform-independent half is shared rather than written twice.

## Status

Scaffold plus the first slice of shared logic. Builds and passes on every target:

| Task | Result |
| --- | --- |
| `:shared:testDebugUnitTest` | 96 tests, 0 failures |
| `:shared:iosSimulatorArm64Test` | 96 tests, 0 failures |
| `:androidApp:testDebugUnitTest` | 29 tests, 0 failures |
| `:androidApp:connectedDebugAndroidTest` | 5 tests, 0 failures (emulator, API 36) |
| `:shared:linkReleaseFrameworkIosArm64` | `DriveMusicShared.framework` produced |
| `:androidApp:assembleDebug` | APK produced |

The 96 shared tests run on both the Android and iOS targets from `commonTest`, which is the point
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
- `playback/PlaybackQueue` — what is playing and what plays next, as a pure state machine. On iOS
  this lives as mutable properties tangled with the audio engine, the download cache and the
  now-playing surface, and almost every playback bug found in that codebase lived here rather than
  in the audio: a queued track that never played, an Up Next list that re-randomised itself, a
  shuffle window that topped up by one instead of twenty. None of those need an audio engine to
  reproduce, and none were caught, because there was nowhere to test them without one. Here every
  operation is a function from state to state and all 25 rules are covered.

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

Verified at three levels, none of which involve trusting the code by reading it:

- **Coefficients, by measurement.** `BiquadTest` pushes sine waves through and checks the
  response, so a -24dB bass shelf is asserted to actually remove 24dB at 50Hz and leave 4kHz
  alone, and each sweep's corner is asserted to be -3dB.
- **The chain end to end.** `TransitionAudioProcessorTest` drives the real processor — configure,
  flush, queue, read — and checks the wiring rather than the math: a plain fade's output envelope
  is asserted to be exactly its volume lane, a zero-volume slot to be digital silence, stereo
  channels not to bleed into each other, and a sample past full scale to clip rather than wrap.
  These are the bugs an audio chain actually has, and none of them show up in a coefficient test.
- **The runtime, on a device.** `CrossfadeEngineInstrumentedTest` runs on the emulator against
  generated WAVs and proves the part that only exists at runtime: an `ExoPlayer` built with a
  custom `DefaultAudioSink` carrying a custom processor really does decode, render and advance,
  and a transition really does complete and hand over. If that wiring were wrong, every test above
  would still pass while nothing made a sound.

`SlotAutomationTest` pins the seam between the shared curves and the engine.

**It has been listened to and it sounds right.** That was the question the whole spike existed to
answer, and it is answered — the rest of the port rests on a foundation that has been heard, not
just measured. `TransitionDemoScreen` is still the app's home screen: pick two files with the
system picker, choose a preset and a length, and hear it. It needs no auth, no networking and no
library.

Every lane of a `TransitionShape` is now applied:

- **Reverb** is hand-written (`Reverb.kt`, four combs into two allpasses). Media3 has no reverb
  processor, and `android.media.audiofx.PresetReverb` attaches to an audio *session*, so it would
  wash both slots and defeat the point. Tested for the thing a feedback network gets wrong: the
  tail decays, stays bounded under sustained full-scale input, and is the same length at any
  sample rate.
- **Beatmatch was never the gap it was written up as.** `PlaybackParameters` carries speed and
  pitch separately and Media3 routes speed through `SonicAudioProcessor`, which time-stretches.
  The real question was whether Sonic survives a custom processor list — `setAudioProcessors`
  replaces it — and it does, because `DefaultAudioProcessorChain` appends its own afterwards.
  Pinned by an instrumented test that measures the rate.
- **The outgoing loop** uses a `ClippingMediaSource` with `REPEAT_MODE_ONE` rather than watching
  the position and seeking back: the ramp ticks at 33ms, so a seek-driven loop would overshoot its
  end by up to a tick and stutter on every pass.

Remaining limitation: sample format is 16-bit PCM only; float output is declined and Media3
converts.

## Running it

The app signs in, browses Drive, downloads, plays, and mixes between tracks.

Sign-in needs a device with a real Google account — the emulator's account flow launches correctly
but there is nothing to sign in *as* unless an account has been added to it. A physical phone is
the easier path.

The Android OAuth client is registered against the app's package name and the signing
certificate's SHA-1, which is why no client ID appears anywhere in the source. A consequence worth
knowing: a build signed with a different key is a different client to Google, so a second
developer machine's debug keystore, or a release build, needs its own SHA-1 registered or
authorization fails with a bare `ApiException`. Get the current one with:

```
./gradlew :androidApp:signingReport
```

Scopes requested are `drive.readonly` and `userinfo.email`. Read-only because this app plays a
library and never modifies one, and asking for write access it does not use is a worse consent
screen for no benefit.

## The app

Five tabs, Material 3 throughout — the *structure* follows the iOS app rather than its look, since
imitating one platform's chrome on the other reads as wrong on both.

- **Home** — Recently added, Made for you (ranked by the shared recommendation model), playlists,
  artists. Built entirely from what is downloaded, so it works offline and costs no network.
- **Browse** — the Drive folder tree, with Play, Shuffle, Download all, and per-track Play
  next / Add to playlist.
- **Library** — everything downloaded, sorted by name, date added, artist or album, with
  Remove download.
- **Playlists** — create, delete, open, reorderless track lists, Download all.
- **Settings** — crossfade on/off and length, auto mix, gapless, volume normalization, a real
  3-band EQ, storage used, sign out, and a confirmed Clear all data.

Now Playing is a full-screen takeover with artwork, scrubbing, the full transport, shuffle and
repeat, and the queue as a bottom sheet. Everything sits inside the window insets: from API 35 an
app is edge-to-edge whether it asks or not, so the choice is not whether content draws behind the
system bars but whether the app admits it.

The EQ is not decorative — it runs as three biquad sections inside the same per-slot chain the
transitions use, applied after the transition's own filtering so a bass swap can never cancel a
standing preference.

## Not started

Sync (the PartyKit room), the transition editor, and track analysis. Analysis
is the significant one: without it the mix-in and mix-out points are unknown, so `TransitionPlan`
falls back to "the transition finishes as the track ends" and the incoming track starts at 0:00.
Everything downstream of it already exists and is tested — see the note on the Java heap above
before porting the FFT.
