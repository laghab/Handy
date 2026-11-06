# Handy for Android

An Android port of [Handy](https://github.com/cjpais/Handy), the free, open source, offline speech-to-text app.

On desktop, Handy is "press a shortcut, speak, and the text is pasted into the focused app." On Android the equivalent is a **voice keyboard**: switch to Handy in any text field, hold the mic and speak, let go, and the text is typed in. Transcription runs on the phone. Audio never leaves the device.

## Install on your phone

1. On your phone, open **[Releases](https://github.com/laghab/Handy/releases/latest)** and download **`Handy-Android-arm64-v8a.apk`**. This works on almost every phone from the last ~7 years. If it won't install, use `Handy-Android-universal.apk`.
2. Open the downloaded file. Android will ask you to allow installs from your browser or file manager the first time.
3. Open Handy and follow the setup: allow the microphone, download a model, then enable and switch to the **Handy voice keyboard**.
4. In any app, tap a text field, switch to Handy (the keyboard icon at the bottom of the screen), hold the pink mic, speak, and let go.

## Features

- **Voice keyboard (IME)** that works in any app. The mic key has three modes, mirroring Handy's shortcut modes:
  - *Hold or tap* (default): hold to talk, or tap once to keep listening hands-free
  - *Tap to toggle*
  - *Hold only*

  Sliding off the mic while holding cancels. The keyboard also has Undo (removes the last dictation), a backspace that repeats and then deletes whole words, punctuation keys, and an Enter key that matches the field's action (Send, Search, Go…).
- **Live transcription while you speak.** Silero VAD splits speech at natural pauses, and each finished segment is transcribed while you keep talking. When you stop, only the last few seconds still need decoding, so text appears roughly 50–200 ms after release, even for long dictations.
- **Same models as Handy, plus faster mobile ones**, all int8-quantised:
  | Model | Languages | Download | Notes |
  |---|---|---|---|
  | Moonshine Tiny | English | 28 MB | fastest, any phone |
  | Moonshine Base | English | 106 MB | |
  | Parakeet 110M | English | 103 MB | default on 4–8 GB phones |
  | **Parakeet V3** | 25 European languages, auto-detect | 639 MB | Handy's flagship, default on 8 GB+ |
  | Whisper Base / Small | 99 languages | 153 / 358 MB | can pin a language |
  | SenseVoice | zh, yue, en, ja, ko | 226 MB | very fast |
- **Handy's post-processing**, ported from `audio_toolkit/text.rs` with its test cases: custom-word correction (Levenshtein + Soundex, n-grams up to 3 words), language-aware filler-word removal, stutter collapsing.
- **Transcribe files**: share a voice note, recording or video to Handy, or pick one in the app. Any format Android can decode works.
- **History**: copy, share, replay the audio, or re-transcribe it with a different model.
- **Settings**: auto-start listening when the keyboard opens, switch back to your normal keyboard after dictating, auto-send, smart spacing, haptics and sounds, pause media while dictating, how long to keep the model loaded, thread count, history retention, and theme.

## How it's optimised for phones

- **Inference**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with ONNX Runtime, using ARM NEON/dot-product kernels and int8 models.
- **Thread count**: picked automatically from the phone's *performance* cores. On big.LITTLE chips, running on every core makes the slow efficiency cores the bottleneck and costs battery.
- **Model lifecycle**: the model starts loading as soon as the keyboard opens, stays warm while you're using it, and is unloaded after an idle timeout (default 3 min) or when Android reports memory pressure.
- **Threading**: all native work runs on one dedicated thread, so the UI never blocks and native calls can't race. The mic runs on its own `URGENT_AUDIO`-priority thread.
- **Audio**: 16 kHz capture with the `VOICE_RECOGNITION` source, which skips the call-style AGC and noise processing that ASR models dislike.
- **Packaging**: native libraries are stored uncompressed and 16 KB page-aligned (Android 15+ ready) and memory-mapped straight from the APK. Per-ABI APK splits make the arm64 download about 29 MB. Release builds use R8.
- **Downloads**: resumable over HTTP Range requests, run in a foreground service, check free space first, extract only the files the model needs, and live in `noBackupFilesDir` so they're never cloud-backed-up.

## Building

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # text-processing / resampler / catalog tests
./gradlew assembleRelease        # app/build/outputs/apk/release/
```

Release signing: create `keystore.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, release builds are signed with the debug key, which is fine for sideloading but not for the Play Store.

Install `app-arm64-v8a-release.apk` on almost any modern phone. Use `app-universal-release.apk` if you're unsure.

### First run

1. Allow the microphone.
2. Download a model. The recommended one is chosen from your phone's RAM.
3. Enable **Handy voice keyboard** in system keyboard settings, then switch to it from the keyboard picker.

### Debug-only test hook

In **debug** builds, if `files/debug_mic.wav` (16 kHz mono 16-bit) exists in the app's private storage, the recorder replays it in real time instead of using the microphone. This lets the full keyboard pipeline be tested end-to-end on an emulator. It is compiled out of release builds.

## Project layout

```
app/src/main/java/com/handy/android/
  ime/        HandyImeService (the keyboard) + Compose UI
  engine/     TranscriptionEngine, DictationSession (live VAD pipeline),
              DictationController, FileTranscriber, DeviceProfile
  audio/      AudioRecorder, WAV I/O, MediaCodec decoder + resampler
  model/      ModelCatalog, ModelManager (downloads), DownloadService
  text/       TextProcessor (port of Handy's text.rs)
  history/    SQLite history + recordings
  settings/   AppSettings
  ui/         App screens (onboarding, home, models, history, settings)
app/src/main/java/com/k2fsa/sherpa/onnx/   vendored sherpa-onnx Kotlin API (JNI-bound, don't rename)
app/src/main/jniLibs/                      sherpa-onnx v2.0.1 + ONNX Runtime prebuilt .so files
```

## Credits and licenses

- [Handy](https://github.com/cjpais/Handy) by CJ Pais: MIT. Concept, post-processing logic and the mascot icon come from upstream.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (k2-fsa): Apache-2.0. ONNX Runtime: MIT.
- Models: Parakeet © NVIDIA (CC-BY-4.0), Whisper © OpenAI (MIT), Moonshine © Useful Sensors (MIT), SenseVoice © FunAudioLLM, Silero VAD (MIT).

This project is MIT licensed (see `LICENSE`).
