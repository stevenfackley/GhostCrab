// Security floors for the *plugin* classpath.
// Plugins requested via the `plugins {}` DSL are resolved into this buildscript's
// `classpath` configuration, which the per-project resolutionStrategy in
// app/build.gradle.kts cannot reach. AGP pulls org.bouncycastle 1.79 in there
// through com.android.tools.build:apkzlib, :builder and com.android.tools:sdk-common.
// That un-pinned 1.79 copy is why Dependabot alerts 66/67 (CVE-2026-8763,
// GHSA-qp49-qgx5-5m26) stayed open even while the app-side pin was maintained --
// it is a second, independent path into the submitted dependency graph.
// Same story for Guava (alert 89, GHSA-xxph-c9ww-hj94): the catalog bump to
// 33.7.2-android only moved the app's own copy; AGP 9.4.1 still requests
// 33.4.0-jre here via com.android.tools:common / :sdk-common, :build:gradle-api,
// :build:aaptcompiler, :build:bundletool and the analytics-library modules.
// Host-side build tooling only; none of this is packaged into the APK.
buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.bouncycastle" &&
                    requested.name.endsWith("-jdk18on") -> {
                    useVersion("1.85")
                    because(
                        "CVE-2026-8763 / GHSA-qp49-qgx5-5m26; transitive via AGP's apkzlib, " +
                            "builder and sdk-common (see Dependabot alerts 66/67)"
                    )
                }
                // Keep the -jre flavour AGP asks for; the app's own -android copy
                // is versioned by gradle/libs.versions.toml, not by this pin.
                // This pin sets the resolved version, so it must lead the advisory.
                requested.group == "com.google.guava" && requested.name == "guava" &&
                    requested.version?.endsWith("-jre") == true -> {
                    useVersion("33.7.2-jre")
                    because(
                        "GHSA-xxph-c9ww-hj94 (guava <= 33.7.1 deserialization memory " +
                            "exhaustion); transitive via AGP 9.4.1 tooling (Dependabot alert 89)"
                    )
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}
