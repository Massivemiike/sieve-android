# R8 keep rules for the release build.
# Compose + AndroidX are handled by the default optimize rules; these cover the
# reflection-sensitive libraries the app links.

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ── kotlinx.serialization (update manifest model) ──────────────────────
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.sieve.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.sieve.app.** { *; }

# ── Room (generated impls reference the entities/DAOs) ─────────────────
-keep class com.sieve.data.db.** { *; }
-keep class com.sieve.data.dao.** { *; }

# ── :queue foreground service + receiver (referenced by manifest strings) ─
-keep class com.sieve.queue.service.QueueService { *; }
-keep class com.sieve.queue.service.QueueCommandReceiver { *; }

# ── youtubedl-android native wrapper (reflection + JNI) ────────────────
-keep class com.yausername.youtubedl_android.** { *; }
-dontwarn com.yausername.youtubedl_android.**

# ── Coroutines / DataStore (okio) ─────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-dontwarn okio.**
