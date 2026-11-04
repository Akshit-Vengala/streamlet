import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

// Bundled native files (compose's appResourcesRootDir: <dir>/windows goes into Windows packages only). Windows has no
// system libmpv, so the installer ships player/native/libmpv-2.dll; Linux packages use the distro's libmpv.
val appResourcesDir = layout.buildDirectory.dir("app-resources")
val stageAppResources = tasks.register<Sync>("stageAppResources") {
    from(rootProject.file("player/native")) { include("libmpv-2.dll"); into("windows") }
    into(appResourcesDir)
    doFirst {
        // A Windows installer without libmpv installs fine but cannot play anything: refuse to build one.
        if (System.getProperty("os.name").lowercase().contains("win"))
            check(rootProject.file("player/native/libmpv-2.dll").isFile) { "player/native/libmpv-2.dll is missing (see player/native/README.md)" }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageAppResources) }

// Installers list jars alphabetically, not in Gradle's order, so classes that :android-shim / :webview-desktop
// override in library-jvm (see ClasspathCheck) would lose. The packaged copy of library-jvm therefore has those
// classes removed, and d2j-external its bundled copy of ASM (asm 9.x wins, as in dev runs): order no longer matters.
val runtimeCp = configurations.named("runtimeClasspath")
val strippedLibs = tasks.register("strippedLibs") {
    val out = layout.buildDirectory.dir("packaging/stripped")
    inputs.files(runtimeCp)
    outputs.dir(out)
    doLast {
        val cp = runtimeCp.get().files
        fun classes(f: File) = ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toSet() }
        val overrides = cp.filter { it.name.startsWith("android-shim") || it.name.startsWith("webview-desktop") }.flatMap(::classes).toSet()
        val asm = cp.filter { it.name.matches(Regex("asm-\\d.*\\.jar")) }.flatMap(::classes).toSet()
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        for (pair in listOf<Pair<String, Set<String>>>("library-jvm" to overrides, "d2j-external" to asm)) {
            val prefix = pair.first; val drop = pair.second
            val src = cp.single { it.name.startsWith(prefix) }
            var removed = 0
            ZipFile(src).use { z ->
                ZipOutputStream(File(dir, src.name).outputStream().buffered()).use { zo ->
                    for (e in z.entries().asSequence()) {
                        if (e.name in drop || (e.name.startsWith("META-INF/") && (e.name.endsWith(".SF") || e.name.endsWith(".RSA")))) { removed++; continue }
                        zo.putNextEntry(ZipEntry(e.name).apply { time = e.time })
                        z.getInputStream(e).use { it.copyTo(zo) }
                        zo.closeEntry()
                    }
                }
            }
            logger.lifecycle("packaging: ${src.name} without $removed shadowed entries")
        }
    }
}
val packagingLibs = tasks.register("packagingLibs") {
    val out = layout.buildDirectory.dir("packaging/libs")
    inputs.files(runtimeCp, strippedLibs)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        val stripped = strippedLibs.get().outputs.files.asFileTree.files
        val jars = runtimeCp.get().files.filterNot { it.name.startsWith("library-jvm") || it.name.startsWith("d2j-external") } + stripped
        // Different artifacts can share a file name (e.g. two groups' "runtime-saveable-desktop"): keep both.
        for (j in jars) {
            var target = File(dir, j.name)
            if (target.exists() && target.length() == j.length() && target.readBytes().contentEquals(j.readBytes())) continue
            if (target.exists()) target = File(dir, j.nameWithoutExtension + "-" + Integer.toHexString(j.absolutePath.hashCode()) + ".jar")
            j.copyTo(target)
        }
    }
}

// Linux packages built on Windows (tools/linux-package/build_linux.py; jpackage itself only targets the host OS): the
// same jars, with skiko's Linux native in place of the host's. CI builds the jpackage .deb/.rpm on Ubuntu instead.
val skikoVersion = "0.150.1" // = skiko-awt on the runtime classpath (checked below)
val linuxSkiko by configurations.creating { isTransitive = false }
dependencies { linuxSkiko("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:$skikoVersion") }
tasks.register("linuxLibs") {
    val out = layout.buildDirectory.dir("packaging/linux-libs")
    dependsOn(packagingLibs, tasks.named("jar"))
    inputs.files(linuxSkiko)
    outputs.dir(out)
    doLast {
        val src = layout.buildDirectory.dir("packaging/libs").get().asFile
        check(src.listFiles()!!.any { it.name == "skiko-awt-$skikoVersion.jar" }) { "skikoVersion is stale: update it to the skiko-awt version in $src" }
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        src.listFiles()!!.filterNot { it.name.startsWith("skiko-awt-runtime-") }.forEach { it.copyTo(File(dir, it.name)) }
        linuxSkiko.files.forEach { it.copyTo(File(dir, it.name)) }
        tasks.named<Jar>("jar").get().archiveFile.get().asFile.copyTo(File(dir, "desktop-app.jar"))
        logger.lifecycle("linux libs: ${dir.listFiles()!!.size} jars in $dir")
    }
}

compose.desktop {
    application {
        mainClass = "cloudstream.desktop.app.MainKt"
        jvmArgs += appJvmArgs
        disableDefaultConfiguration()
        mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        fromFiles(tasks.named<Jar>("jar").flatMap { it.archiveFile }, fileTree(layout.buildDirectory.dir("packaging/libs")))
        dependsOn(packagingLibs.get(), tasks.named("jar").get())
        nativeDistributions {
            appResourcesRootDir.set(appResourcesDir)
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
                iconFile.set(project.file("icons/streamlet.ico"))
            }
            linux {
                packageName = "streamlet"
                iconFile.set(project.file("icons/streamlet.png"))
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
