dependencies {
    api(project(":contracts"))
    // Implements the android.webkit.WebView seam (WebViewBridge) over JCEF.
    implementation(project(":android-shim"))
    // Upstream `library` (NiceHttp, OkHttp, WebViewResolver expect class) via :core.
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // upstream `library` keeps these as `implementation`, so declare them here; versions match upstream's catalog.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    // Pinned: CEF 143 / Chromium 143. Natives are downloaded on first run (see JcefRuntime).
    implementation("me.friwi:jcefmaven:143.0.14")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Tests that need a real CEF are opt-in: ./gradlew :webview-desktop:test -Pjcef
    // (sets -Dcloudstream.jcef.tests=true). CI-safe by default.
    if (project.hasProperty("jcef")) systemProperty("cloudstream.jcef.tests", "true")
    testLogging { showStandardStreams = true }
}

// Ad-hoc manual check against real sites:
//   ./gradlew :webview-desktop:smoke -Purl=https://... [-Pregex=...] [-Pmode=resolve|clearance]
tasks.register<JavaExec>("smoke") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cloudstream.desktop.webview.JcefSmokeKt")
    systemProperty("cloudstream.jcef.debug", "1")
    if (project.hasProperty("loadurl")) systemProperty("cloudstream.jcef.loadurl", "1")
    args(
        (project.findProperty("mode") ?: "resolve").toString(),
        (project.findProperty("url") ?: "https://example.com").toString(),
        (project.findProperty("regex") ?: "example").toString(),
    )
}
