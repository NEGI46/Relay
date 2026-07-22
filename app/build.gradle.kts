import java.time.Instant
import java.util.Collections
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.relay"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.relay"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Production/pilot variants deliberately ship no trust anchor until the operator supplies
        // an approved public bundle. A variant may opt into a public-only asset by overriding this.
        buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ASSET", "\"\"")
        // A test Root is never a release/pilot trust anchor, even if a misconfigured asset is
        // accidentally packaged. Debug/local development may explicitly allow TEST fixtures.
        buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ENVIRONMENTS", "\"PILOT,PRODUCTION\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        val gitCommit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"${Instant.now()}\"")
    }

    buildTypes {
        getByName("debug") {
            // Optional local developer asset; it is absent by default and must never contain a
            // private key. Release-derived variants inherit the empty production setting.
            buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ASSET", "\"relay-regional-roots.debug.json\"")
            buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ENVIRONMENTS", "\"TEST,PILOT\"")
        }
        create("localDev") { initWith(getByName("debug")); matchingFallbacks += listOf("debug") }
        create("pilotRelease") { initWith(getByName("release")); matchingFallbacks += listOf("release") }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * Packaging guardrail for Phase 0A. A future approved public Root may legitimately be packaged,
 * but test fixtures and private Root material must never cross into release-derived APKs.
 */
tasks.register("verifyNoTestTrustArtifactsInReleaseApks") {
    dependsOn("assembleRelease", "assemblePilotRelease")
    doLast {
        val apks = fileTree(layout.buildDirectory.dir("outputs/apk").get().asFile) {
            include("release/*.apk", "pilotRelease/*.apk")
        }.files
        check(apks.size >= 2) { "Expected release and pilotRelease APKs for trust-artifact inspection" }
        apks.forEach { apk ->
            ZipFile(apk).use { archive ->
                Collections.list(archive.entries())
                    .asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.startsWith("assets/") }
                    .forEach { entry ->
                        check(!entry.name.contains("test", ignoreCase = true)) {
                            "Test asset must not be packaged in ${apk.name}: ${entry.name}"
                        }
                        if (entry.size in 1L..(512L * 1024)) {
                            val content = archive.getInputStream(entry).use { input ->
                                input.readBytes().toString(Charsets.UTF_8)
                            }
                            check(!content.contains("TEST ONLY", ignoreCase = true)) {
                                "Test trust material must not be packaged in ${apk.name}: ${entry.name}"
                            }
                            check(!content.contains("rootSigningPrivateKey") &&
                                !content.contains("privateKey", ignoreCase = true)
                            ) {
                                "Private Root material must not be packaged in ${apk.name}: ${entry.name}"
                            }
                        }
                    }
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":relay-protocol"))
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.google.play.services.nearby)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.zxing.core)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.app.cash.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
