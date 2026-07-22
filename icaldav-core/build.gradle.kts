plugins {
    kotlin("jvm") version libs.versions.kotlin
}

repositories {
    mavenCentral()
}

dependencies {
    // iCalendar parsing - handles RFC 5545 compliance.
    // ical4j 4.2.2 is deliberately confined to THIS subproject; the MCP's own
    // source depends on :icaldav-core, never on ical4j directly.
    implementation("org.mnode.ical4j:ical4j:4.2.2")

    // Kotlin coroutines (aligned with the root version catalog)
    implementation(libs.kotlinx.coroutines.core)

    // Testing (JUnit 5)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
