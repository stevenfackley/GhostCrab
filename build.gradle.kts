// Bouncy Castle floor for the *plugin* classpath.
// Plugins requested via the `plugins {}` DSL are resolved into this buildscript's
// `classpath` configuration, which the per-project resolutionStrategy in
// app/build.gradle.kts cannot reach. AGP pulls org.bouncycastle 1.79 in there
// through com.android.tools.build:apkzlib, :builder and com.android.tools:sdk-common.
// That un-pinned 1.79 copy is why Dependabot alerts 66/67 (CVE-2026-8763,
// GHSA-qp49-qgx5-5m26) stayed open even while the app-side pin was maintained --
// it is a second, independent path into the submitted dependency graph.
// Host-side build tooling only; none of this is packaged into the APK.
buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle" &&
                requested.name.endsWith("-jdk18on")
            ) {
                useVersion("1.85")
                because(
                    "CVE-2026-8763 / GHSA-qp49-qgx5-5m26; transitive via AGP's apkzlib, " +
                        "builder and sdk-common (see Dependabot alerts 66/67)"
                )
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
