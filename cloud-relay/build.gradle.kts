plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("org.postgresql:postgresql:42.7.4")
    testImplementation(kotlin("test"))
}
application { mainClass.set("com.example.relay.cloudrelay.MainKt") }
