# Keep JNA bridge classes referenced by reflection in the native edge runtime.
-keep class com.sun.jna.** { *; }
-keep class ai.synheart.core.edge.engine.RuntimeBridge { *; }
