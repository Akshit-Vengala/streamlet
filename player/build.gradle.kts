dependencies {
    api(project(":contracts"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // DLL-dependent tests are opt-in: run with -Dcloudstream.mpv.tests=true
    systemProperty("cloudstream.mpv.tests", System.getProperty("cloudstream.mpv.tests") ?: "false")
}
