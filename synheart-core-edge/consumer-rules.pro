# Keep JNA bridge classes referenced by reflection in synheart-core-runtime.
-keep class com.sun.jna.** { *; }
-keep class ai.synheart.core.edge.engine.RuntimeBridge { *; }
