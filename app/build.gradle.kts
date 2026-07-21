import java.time.Instant
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val relayReleaseStoreFile = providers.gradleProperty("relay.release.store.file").orNull
val relayReleaseStorePassword = providers.gradleProperty("relay.release.store.password").orNull
val relayReleaseKeyAlias = providers.gradleProperty("relay.release.key.alias").orNull
val relayReleaseKeyPassword = providers.gradleProperty("relay.release.key.password").orNull
val relayReleaseSigningConfigured = listOf(
    relayReleaseStoreFile,
    relayReleaseStorePassword,
    relayReleaseKeyAlias,
    relayReleaseKeyPassword,
).all { !it.isNullOrBlank() }

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

    val releaseSigningConfig = if (relayReleaseSigningConfigured) {
        signingConfigs.create("organizationRelease") {
            storeFile = file(requireNotNull(relayReleaseStoreFile))
            storePassword = requireNotNull(relayReleaseStorePassword)
            keyAlias = requireNotNull(relayReleaseKeyAlias)
            keyPassword = requireNotNull(relayReleaseKeyPassword)
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "false")
            signingConfig = releaseSigningConfig
        }
        create("localDev") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "true")
        }
        create("pilotRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "false")
        }
    }

    sourceSets {
        // localDev shares the debug-only cleartext policy and test-only manifest components.
        getByName("localDev") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            res.srcDir("src/debug/res")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// A local unsigned/locally signed assembleRelease is useful for engineering inspection, but is
// never a formal release. CI turns this explicit gate on and fails before publication when the
// organization signing material has not been supplied through the approved secret store.
val requireOrganizationReleaseSigning = providers.gradleProperty("relay.require.release.signing")
    .orNull
    ?.equals("true", ignoreCase = true) == true
tasks.matching { it.name == "assembleRelease" || it.name == "assemblePilotRelease" }.configureEach {
    doFirst {
        if (requireOrganizationReleaseSigning && !relayReleaseSigningConfigured) {
            throw GradleException(
                "Organization Android signing material is required for a formal release; do not publish an unsigned or locally signed APK.",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
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
