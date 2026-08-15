import java.util.TimeZone

plugins {
    kotlin("jvm") version libs.versions.kotlin
    kotlin("plugin.serialization") version libs.versions.kotlin
    application
}

group = "org.onekash.mcp"
version = "3.2.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
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

    // ICS parsing/generation — vendored icaldav-core (ical4j 4.3.0 confined to that
    // subproject). The MCP source depends on icaldav's API only; ical4j is NOT a
    // direct dependency here and must not be re-added (two ical4j majors on one
    // classpath collide on net.fortuna.ical4j.* classes).
    implementation(project(":icaldav-core"))

    // Logging
    implementation(libs.slf4j.simple)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform {
        // Live iCloud integration tests are tagged @Tag("integration") and hit the
        // real caldav.icloud.com. They are EXCLUDED by default so `./gradlew test`
        // stays hermetic and side-effect-free. Opt in with the -Pintegration flag:
        //
        //   ./gradlew test -Pintegration \
        //     --tests "org.onekash.mcp.calendar.live.*"
        //
        // Even when included, each test self-skips (JUnit assumeTrue) unless iCloud
        // credentials are present (env ICLOUD_USERNAME/ICLOUD_PASSWORD or
        // ICLOUD_APP_PASSWORD, or a local.properties entry).
        if (!project.hasProperty("integration")) {
            excludeTags("integration")
        }
    }
    // Forward the JVM default time zone to the forked test workers so that
    // `-Duser.timezone=Asia/Seoul` (or any zone) actually reaches the tests.
    // Without this, workers always run in the daemon's zone and timezone
    // regressions stay hidden on a UTC CI box. `user.timezone` is only populated
    // by an explicit `-Duser.timezone`; an ambient OS/`TZ` zone reads back blank,
    // so fall back to the resolved default so those runs are exercised too.
    systemProperty(
        "user.timezone",
        System.getProperty("user.timezone")?.takeIf { it.isNotBlank() }
            ?: TimeZone.getDefault().id
    )
    // Integration tests share a single real iCloud account, so serialize them to
    // avoid ETag races and cross-test event collisions on the same calendar.
    if (project.hasProperty("integration")) {
        maxParallelForks = 1
    }
    // Surface live-test stdout (cleanup summaries, wire dumps) when opted in, so
    // integration runs are auditable. Off for the default hermetic run.
    if (project.hasProperty("integration")) {
        testLogging { showStandardStreams = true }
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.onekash.mcp.calendar.MainKt"
        attributes["Implementation-Version"] = project.version.toString()
    }
}

// Create fat JAR for distribution
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "org.onekash.mcp.calendar.MainKt"
        // Read back at runtime as the MCP serverInfo version, so the version lives
        // in exactly one place (build.gradle.kts) instead of a hardcoded literal.
        attributes["Implementation-Version"] = project.version.toString()
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
