plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    // Fuzz the real production decoders, not copies.
    implementation(project(":shared"))
    implementation(project(":relay-protocol"))
    implementation(libs.kotlinx.serialization.json)

    // Jazzer's JUnit5 integration. In regression mode (the default here) each @FuzzTest runs the
    // committed seed corpus deterministically on the JVM; continuous libFuzzer fuzzing is opt-in via
    // the JAZZER_FUZZ environment variable and only supported on Linux/macOS drivers.
    testImplementation(libs.jazzer.junit)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    // Keep the deterministic regression lane hermetic and quick; real fuzzing is opt-in.
    systemProperty("jazzer.instrument", "com.example.relay.**")
}
