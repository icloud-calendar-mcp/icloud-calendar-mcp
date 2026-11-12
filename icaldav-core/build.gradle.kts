import java.util.TimeZone

plugins {
    kotlin("jvm") version libs.versions.kotlin
    // Shares the property-test harness (testutil.PropertyRuns) with the root module's tests.
    `java-test-fixtures`
}

repositories {
    mavenCentral()
}

dependencies {
    // iCalendar parsing - handles RFC 5545 compliance.
    // ical4j 4.4.0 is deliberately confined to THIS subproject; the MCP's own
    // source depends on :icaldav-core, never on ical4j directly.
    implementation("org.mnode.ical4j:ical4j:4.4.0")

    // Kotlin coroutines (aligned with the root version catalog)
    implementation(libs.kotlinx.coroutines.core)

    // Testing (JUnit 5)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // Property tests (checkAll, generators, shrinking) through testutil.PropertyRuns, run from
    // JUnit 5 tests. The excluded modules aren't needed by checkAll: a bytecode agent with native
    // access (kotlinx-coroutines-debug pulls byte-buddy and jna), classpath scanning (classgraph),
    // an XML serialization stack (xmlutil), regex-driven generators (rgxgen, for
    // Arb.stringPattern) and assertion diffs (java-diff-utils, for Kotest's matchers).
    testFixturesApi(libs.kotest.property) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
        exclude(group = "io.github.pdvrieze.xmlutil")
        exclude(group = "io.github.classgraph")
        exclude(group = "com.github.curious-odd-man")
        exclude(group = "community.flock.kotlinx.rgxgen")
        exclude(group = "io.github.java-diff-utils")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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
    // Property tests (testutil.PropertyRuns): -PpropertySeed=<seed>|random replays a printed seed
    // or draws a new one, -PpropertyIterations=<n> multiplies the iterations. Never up to date
    // while set, so a repeated random run draws again.
    listOf("propertySeed", "propertyIterations").forEach { key ->
        if (project.hasProperty(key)) {
            systemProperty(key, project.property(key).toString())
            outputs.upToDateWhen { false }
        }
    }
}
