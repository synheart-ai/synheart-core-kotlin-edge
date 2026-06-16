# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.0.4] - 2026-06-15

### Added
- `ComputeProfile.edgeMode` (`off` / `shadow` / `canonical`) forwarded to the
  native runtime as `compute_profile.edge_mode`, stamping compute provenance
  (`session_role`) on emitted HSI. Synced with the canonical wire contract and
  the Swift edge SDK; default `canonical` preserves prior behaviour.
- `hsi_version` carried on the HSI artifact envelope (extracted from the inner
  payload) so consumers can validate the payload version without parsing it.
  Tolerant fallback to a sentinel for older producers.
- Observability guards: a dropped-frame counter driven by the native handle's
  `last_error`, and a WARN when an emitted `hsi_version` falls outside the
  supported set. No wire shape changes.
- Multi-tenant persistence: `EdgeSessionManager`, `EdgeOutbox`, and `PhoneRelay`
  now accept optional namespace parameters (prefs file / key / directory name)
  defaulting to the current canonical values, so two SDK-based apps don't
  collide on disk.

### Security
- Outbox artifacts and edge session manifests are now encrypted at rest via
  Jetpack Security `EncryptedFile` (AES-256-GCM, Android Keystore master key).
  The on-the-wire / JSON shape is unchanged; only the at-rest bytes differ.
  Falls back to plaintext at rest only when the Keystore is unavailable.
- Path-traversal hardening: `EdgeOutbox` and `EdgeSessionManager` reject any
  artifact / session id that isn't a safe path token, so a phone-supplied id
  can never escape the outbox / sessions directory.
- Sender authentication on the exported `PhoneListenerService`: incoming
  commands are dropped unless they originate from a currently-connected
  (paired) Wear node, failing closed on any node-lookup error.

### Changed
- Durable outbox writes are now atomic (temp file + atomic rename), so a crash
  mid-write can never leave a half-written artifact.
- `RuntimeBridge.close()` is idempotent (guarded against double-free).
- Publishing migrated to the `com.vanniktech.maven.publish` plugin, which
  provides the `publishAndReleaseToMavenCentral` task the release workflow runs.
- When no `EdgeSessionManager` is injected, the engine derives a per-instance
  random runtime `subject_id` instead of a shared constant, so personalization
  is never cross-contaminated across users.

### Fixed
- `SessionPreset.toJson()` now writes `edge_mode`, so a preset round-trips
  losslessly through `ComputeProfile.fromJson`.

### Removed
- Dead `MotionAccumulator` utility (the engine pipes motion straight to the
  runtime and never consumed it).

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
