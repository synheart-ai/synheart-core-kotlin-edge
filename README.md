# synheart-core-edge (Kotlin)

Light Synheart Core SDK for **Wear OS / Android Wear**. The full
[`synheart-core-kotlin`](https://github.com/synheart-ai/synheart-core-kotlin)
SDK is too heavy for a watch — this package ships the minimum needed to run an
on-device session and relay results to a paired phone.

> **Status:** `0.0.1` — initial extraction from the
> [`synheart-edge-watch-android`](https://github.com/synheart-ai/synheart-edge-watch-android)
> reference app. Source has been repackaged from `ai.synheart.wear.watch.*` to
> `ai.synheart.core.edge.*` so it can be consumed as a standalone library.
> Kotlin classes are public by default — the SDK is importable as soon as it's
> tagged. API surface review (which types should stay `internal`) lands in `0.0.2`.

## Scope

What's in (`ai.synheart.core.edge.*`):

- **engine** — `WatchSessionEngine`, `EdgeSessionManager`, `EdgeOutbox`,
  `MotionAccumulator`, `RuntimeBridge`
- **sensor** — `HeartRateSensor` (Health Services), `MotionSensor` (SensorManager)
- **relay** — `PhoneRelay`, `PhoneListenerService` (Wearable Data Layer)
- **models** — `SessionConfig`, `SessionPreset`, `ComputeProfile`, `SessionEvent`

What's out (ships in `synheart-core-kotlin`, not here):

- Cloud sync / direct HTTPS upload
- Authentication (phone owns identity)
- Lab ingest, longitudinal SRM, baselines
- Behavior / consent UI

## Architecture

```
sensors  →  WatchSessionEngine  →  RuntimeBridge (JNA) → synheart-core-runtime
                  │
                  ├──→  EdgeOutbox    (local artifact persistence)
                  └──→  PhoneRelay    (relay to phone via Wearable Data Layer)
```

Native runtime (`synheart-core-runtime`) is loaded via JNA — no Maven
dependency declared on it. Consumers must bundle the appropriate `.so`
in their Wear OS app's `jniLibs/`.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("ai.synheart:synheart-core-edge:0.0.1")
}
```

## Size budget

| Metric | Target | Status |
|---|---|---|
| Compiled AAR (excl. native runtime `.so`) | < 500 KB | not yet measured |
| Public top-level types | ≤ 12 | TBD in 0.0.2 |
| Transitive Synheart SDK deps | 0 | ✓ 0 |

## Reference app

[`synheart-edge-watch-android`](https://github.com/synheart-ai/synheart-edge-watch-android)
is the canonical consumer. It will migrate from its bundled
`ai.synheart.wear.watch.*` source to this SDK once `0.1.0` is tagged.

## License

[Apache-2.0](LICENSE)
