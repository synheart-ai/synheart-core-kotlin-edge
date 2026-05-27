# synheart-core-edge (Kotlin)

Light Synheart Core SDK for **Wear OS / Android Wear**. The full [`synheart-core-kotlin`](https://github.com/synheart-ai/synheart-core-kotlin) SDK is too heavy for a watch — this package ships the minimum needed to run an on-device session and relay results to a paired phone.

**Status:** `0.0.3` — public API. Mirrors [`synheart-core-swift-edge`](https://github.com/synheart-ai/synheart-core-swift-edge) one-for-one (same engine surface, same state machine, same model types). See [docs.synheart.ai/synheart-core/edge](https://docs.synheart.ai/synheart-core/edge) for the cross-platform guide.

## Scope

What's in (`ai.synheart.core.edge.*`):

- **engine** — `WatchSessionEngine`, `EdgeSessionManager`, `EdgeOutbox`, `MotionAccumulator`, `RuntimeBridge`, `WatchSessionState`
- **sensor** — `MotionSensor` (SensorManager). HR comes from a `BiosignalProvider` (the host supplies one — typically `HealthServicesBiosignalProvider` wrapping Wear OS Health Services)
- **relay** — `PhoneRelay`, `PhoneListenerService` (Wearable Data Layer)
- **models** — `SessionConfig`, `SessionPreset`, `ComputeProfile`, `SessionEvent`, `EdgeTypes`

What's out (ships in `synheart-core-kotlin`, not here):

- Cloud sync / direct HTTPS upload
- Authentication (phone owns identity)
- Lab ingest, longitudinal SRM, baselines
- Behavior / consent UI

## Install

Until the SDK publishes to Maven Central, consume via composite build:

```kotlin
// settings.gradle.kts
val coreEdge = providers.environmentVariable("SYNHEART_CORE_EDGE_LOCAL").orNull
if (!coreEdge.isNullOrBlank()) {
    includeBuild(coreEdge)
}

// wear/build.gradle.kts
dependencies {
    implementation("ai.synheart:synheart-core-edge")
    implementation("ai.synheart:synheart-session:0.1.0")
}
```

Targets: Wear OS 4 / API 33+. (Pixel Watch 1 is `armeabi-v7a` only — keep that ABI in your `:wear` jniLibs.)

## Quickstart

```kotlin
class WatchApp : Application() {
    private val provider by lazy { HealthServicesBiosignalProvider(this) }
    private val motionSensor = MotionSensor()
    private val outbox by lazy { EdgeOutbox(this) }
    private val sessionManager by lazy { EdgeSessionManager(this) }

    val engine: WatchSessionEngine by lazy {
        WatchSessionEngine(
            provider = provider,
            motionSensor = motionSensor,
            outbox = outbox,
            sessionManager = sessionManager,
        )
    }

    override fun onCreate() {
        super.onCreate()
        motionSensor.init(this)
    }
}
```

`HealthServicesBiosignalProvider` (a `BiosignalProvider` impl that wraps Wear OS `MeasureClient`) is provided by the host — not the SDK — because Wear platform binding is the host's choice. The reference [Life wear module](https://github.com/synheart-ai/synheart-life-mobile-app/tree/main/apps/synheart_life/android/wear) has one you can copy.

Swap in a custom HR source the same way (BLE chest-strap, mock for tests, etc.) by implementing `ai.synheart.session.BiosignalProvider`.

## Architecture

```
sensors  →  WatchSessionEngine  →  RuntimeBridge (JNA) → synheart-core-runtime
                  │
                  ├──→  EdgeOutbox    (local artifact persistence)
                  └──→  PhoneRelay    (Wearable Data Layer)
```

The native runtime binary (`libsynheart_core_runtime.so`) is loaded via JNA. When absent, the engine falls back to `STREAM` mode and surfaces raw samples via `bioSamples` for the host to relay. When present, the engine runs the edge HSI pipeline locally in `COMPUTE_LOCAL` mode.

Build the `.so` with `--features edge` (not `--no-default-features` alone — that strips the `synheart_core_edge_*` exports) and drop into `wear/src/main/jniLibs/armeabi-v7a/`. The reference Life wear module includes a `:wear:vendorEdgeRuntime` Gradle task that shells out to `cargo ndk … --features edge` and vendors automatically when `SYNHEART_CORE_RUNTIME_LOCAL` is set.

## Session API

```kotlin
engine.startSession(config, requestedMode = null)   // null → resolves to COMPUTE_LOCAL or STREAM
engine.pauseSession()
engine.resumeSession()
engine.stopSession()
engine.startEdgeSession(preset)            // standalone watch session
engine.acknowledgeArtifacts(ids)           // after phone confirms relay receipt
```

State: `IDLE | STARTING | RUNNING | PAUSED | STOPPING | SYNCING | ERROR`

Flows: `engine.state`, `engine.hrEvents`, `engine.events`, `engine.bioSamples` (STREAM-only).

## See also

- **Cross-platform guide:** [docs.synheart.ai/synheart-core/edge](https://docs.synheart.ai/synheart-core/edge)
- **Swift parallel:** [synheart-core-swift-edge](https://github.com/synheart-ai/synheart-core-swift-edge)
- **Reference wear app:** [synheart-edge-watch-android](https://github.com/synheart-ai/synheart-edge-watch-android)

## License

[Apache-2.0](LICENSE)
