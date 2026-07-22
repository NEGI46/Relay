plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application { mainClass.set("com.example.relay.pcgateway.MainKt") }
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":relay-protocol"))
    implementation(project(":shared"))
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json.client)
    implementation(libs.sqlite.jdbc)
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
}
