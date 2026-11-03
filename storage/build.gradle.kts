dependencies {
    api(project(":contracts"))
    implementation(libs.kotlinx.coroutines.core)
    // Literal coordinates: root version catalog is coordinator-owned (see PROGRESS.md cross-track note).
    api("com.fasterxml.jackson.core:jackson-databind:2.13.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test> { useJUnitPlatform() }
