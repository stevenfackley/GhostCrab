# Add project specific ProGuard rules here.
# Keep Kotlin metadata
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }

# Koin
-keep class org.koin.** { *; }
