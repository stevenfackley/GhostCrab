# ── Metadata ─────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }

# ── Ktor ─────────────────────────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ── kotlinx.serialization ────────────────────────────────────────────────────
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Keep our @Serializable wire types so R8 doesn't strip serializer companions
-keep @kotlinx.serialization.Serializable class com.openclaw.ghostcrab.** { *; }

# ── Koin ─────────────────────────────────────────────────────────────────────
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.debug.*

# ── Domain sealed hierarchies (preserve subtype names for error messages) ────
-keep class com.openclaw.ghostcrab.domain.exception.** { *; }
-keep class com.openclaw.ghostcrab.domain.model.** { *; }
