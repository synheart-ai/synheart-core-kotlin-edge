# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.0.3] - 2026-05-26

### Fixed
- Gradle wrapper bumped to 8.11.1 so the Android Gradle Plugin's minimum
  requirement is satisfied; previously a fresh clone failed with
  "Minimum supported Gradle version is 8.11.1. Current version is 8.9".

### Changed
- Trimmed `WatchSessionEngine` KDoc to local code-level intent only.

## [0.0.1] - 2026-05-07

Initial release. Watch-side SDK for Wear OS / Android, extracted from
[`synheart-edge-watch-android`](https://github.com/synheart-ai/synheart-edge-watch-android)
and recomposed against the same sibling SDKs the phone SDK depends on.

### Architecture

This SDK is a **thin FFI wrapper** around `synheart-core-runtime`. All math
and signal derivation runs in the native runtime, not in Kotlin. The watch
SDK is just: sensor capture → push raw samples (`pushHr` / `pushRr` /
`pushAccel`) to runtime → outbox runtime-emitted artifacts → relay to phone.

### Composition

- **Biosignals** flow through any `BiosignalProvider` from
  `synheart-session-kotlin`. Typical wiring on Wear OS:
  `HealthConnectBiosignalProvider` wrapping `HealthConnectAdapter` from
  `synheart-wear-kotlin`. Multi-device by design — same abstraction the
  phone SDK uses (Health Connect / future BLE / Whoop / Garmin).
- **Motion** (accelerometer) is captured locally via `SensorManager`
  since Health Connect doesn't stream raw IMU. Raw `(t, x, y, z)` samples
  are pushed straight to the runtime; no client-side aggregation.
- **Session lifecycle** is owned by `WatchSessionEngine` (state machine,
  timers, outbox). A future release will move lifecycle ownership into
  `synheart.session.SessionEngine` directly.
- **Native runtime** (`synheart-core-runtime`) is loaded via JNA; no Maven
  dependency declared. Apps must bundle the appropriate `.so` files.

### Engine modes

`EngineMode` selects how the watch participates in a session:

- `STREAM` — raw `BiosignalSample`s are surfaced via
  `bioSamples: SharedFlow<BiosignalSample>` for the host app to relay to
  the paired phone. Runtime not loaded.
- `COMPUTE_LOCAL` — raw samples piped to `synheart-core-runtime` via FFI;
  HSI artifacts emitted by the runtime are persisted into `EdgeOutbox` and
  relayed to phone.

Default resolution at `startSession`: try to load the runtime; if available
use `COMPUTE_LOCAL`, otherwise fall back to `STREAM`. Caller can override
via `startSession(config, requestedMode)`.

### Build artifact

- Maven coordinates: `ai.synheart:synheart-core-edge:0.0.1`
- `com.android.library`, `compileSdk 34`, `minSdk 30` (Wear OS 3.0),
  Kotlin 2.1.0, AGP 8.7.3
- Source repackaged from `ai.synheart.wear.watch.*` (in the reference app)
  to `ai.synheart.core.edge.*`
- Sonatype Central Portal publish wired through `publish.yml`; credentials
  must be added to GitHub secrets before `Release` runs

### Code hygiene at extraction

Pulled out of the reference watch app and **not carried forward** into this
SDK:

- `MotionAccumulator` (local RMS-g aggregation) — runtime owns it.
- In-process HR / RR sample buffers (`hrSamples`, `rrIntervals`) and the
  local computation of `hr_mean_bpm`, `hr_sdnn_ms`, `rmssd_ms` — runtime
  emits authoritative values in HSI JSON.
- Bespoke `HeartRateSensor` (Health Services-only) — replaced by the
  `BiosignalProvider` abstraction from `synheart-session-kotlin`.
- `sensor/HrSample.kt` — replaced by `BiosignalSample` from
  `ai.synheart.session`.
- Per-frame metric synthesis from accumulated buffers — replaced by passing
  the runtime's HSI dict through unchanged.

### Notes

- Kotlin classes are public by default, so the library is importable as
  soon as the artifact is published. Scope refinement (marking internals
  as `internal`) lands in `0.0.2`.
- `WatchSessionEngine` constructor takes
  `provider: ai.synheart.session.BiosignalProvider` as a required
  argument. Use `MockBiosignalProvider` from `synheart-session-kotlin`
  for unit tests.

### Planned (0.0.2)

- Drive session lifecycle through `synheart.session.SessionEngine`
  directly rather than the parallel timer-driven loop. Connect runtime
  HSI output to `engine.ingestHsiMetrics(...)` so frames carry
  authoritative metrics from native code.
- API surface review — explicitly mark internal types as `internal`,
  settle on the v0 public surface.
- CI binary-size assertion to enforce the 500 KB AAR budget.
- Verify build resolves once `ai.synheart:synheart-session:0.1.0` and
  `ai.synheart:synheart-wear:0.3.0` are published to Maven Central.
