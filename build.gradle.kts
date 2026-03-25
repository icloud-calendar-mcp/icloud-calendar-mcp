plugins {
    kotlin("jvm") version libs.versions.kotlin
    kotlin("plugin.serialization") version libs.versions.kotlin
    application
}

group = "org.onekash.mcp"
version = "3.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.onekash.mcp.calendar.MainKt")
}

dependencies {
    // MCP SDK
    implementation(libs.mcp.kotlin.sdk)

    // Ktor (for MCP transport)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.cio)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Kotlinx IO (for MCP transport)
    implementation(libs.kotlinx.io.core)

    // HTTP Client (for CalDAV)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // ICS Parsing
    implementation(libs.ical4j)

    // Logging
    implementation(libs.slf4j.simple)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.onekash.mcp.calendar.MainKt"
    }
}

// Create fat JAR for distribution
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "org.onekash.mcp.calendar.MainKt"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
