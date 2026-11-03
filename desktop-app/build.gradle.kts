import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Versions mirror upstream (Kotlin 2.4.0, Compose Multiplatform 1.12.0, Material3 1.12.0-alpha03).
    id("org.jetbrains.compose") version "1.12.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

dependencies {
    api(project(":contracts"))
    // ORDER MATTERS: modules that shadow library-jvm classes (ContextHelper_jvmKt, WebViewResolver,
    // CloudflareKiller) must precede :core / library-jvm on the runtime classpath. Verified at startup (ClasspathCheck).
    implementation(project(":android-shim"))
    implementation(project(":webview-desktop"))
    implementation(project(":storage"))
    implementation(project(":plugin-loader"))
    implementation(project(":player"))
    implementation(libs.jna)
    implementation(libs.jna.platform) // DPAPI for account logins (sync/SecretBox)
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    compileOnly("org.jsoup:jsoup:1.22.1") // Addic7ed scraping; the runtime copy comes with library-jvm (same version)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    implementation(compose.desktop.currentOs) {
        // compose.desktop.currentOs pulls in Material 2; we use Material 3 only.
        exclude(group = "org.jetbrains.compose.material", module = "material")
    }
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// App version and the public GitHub repo (OWNER/NAME) whose Releases the in-app updater checks. Set in gradle.properties
// or with -Papp.version=... -Papp.updateRepo=...; a blank repo turns the updater off.
val appVersion: String = providers.gradleProperty("app.version").orElse("1.0.0").get()
val appUpdateRepo: String = providers.gradleProperty("app.updateRepo").orElse("").get()
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", appVersion)
    inputs.property("updateRepo", appUpdateRepo)
    outputs.dir(out)
    doLast {
        out.get().file("app-build.properties").asFile.apply { parentFile.mkdirs() }
            .writeText("version=$appVersion\nupdateRepo=$appUpdateRepo\n")
    }
}
sourceSets["main"].resources.srcDir(generateBuildInfo)

val appJvmArgs = listOf(
    "-Xmx3g", // plugin conversion (dex2jar) of big plugins needs ~3 GB
    "--enable-native-access=ALL-UNNAMED", // JNA / skiko / JCEF
    "-Dfile.encoding=UTF-8",
)

compose.desktop {
    application {
        mainClass = "cloudstream.desktop.app.MainKt"
        jvmArgs += appJvmArgs
        nativeDistributions {
            // jpackage builds for the host OS only: run packageDistributionForCurrentOs on Windows for MSI/EXE,
            // on Linux for DEB/RPM (AppImage via packageAppImage). See .github/workflows/build.yml.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "Streamlet"
            packageVersion = appVersion
            description = "Streamlet: a desktop player for CloudStream extensions"
            vendor = "Streamlet"
            licenseFile.set(rootProject.file("LICENSE"))
            // Plugins are arbitrary JVM code (java.sql, java.naming, ...): ship the full runtime rather than a guessed jlink set.
            includeAllModules = true
            windows {
                menu = true; shortcut = true; dirChooser = true; perUserInstall = true
                menuGroup = "Streamlet"
                upgradeUuid = "8c6f2a3e-4b1d-4e2a-9f57-3d2c1b0a9e71"
            }
            linux {
                packageName = "streamlet"
                debMaintainer = "streamlet@users.noreply.github.com"
                menuGroup = "AudioVideo;Video;Player"
                appCategory = "Video"
                shortcut = true
            }
        }
    }
}

// Dev runs (./gradlew :desktop-app:run) start in desktop-app/, where the relative player/native lookup misses libmpv.
tasks.withType<JavaExec>().configureEach {
    rootProject.file("player/native/libmpv-2.dll").takeIf { it.isFile }?.let { systemProperty("cloudstream.mpv.dll", it.absolutePath) }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Live / plugin-executing tests are opt-in: -Pcs.live=true -Pcs.cs3dir=<dir with .cs3>
    systemProperty("cs.live", providers.gradleProperty("cs.live").orElse("false").get())
    systemProperty("cs.cs3dir", providers.gradleProperty("cs.cs3dir").orElse("").get())
}
