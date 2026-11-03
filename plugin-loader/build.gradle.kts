dependencies {
    api(project(":contracts"))
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // dex2jar (ThexXTURBOXx fork), published on Maven Central.
    implementation("de.femtopedia.dex2jar:dex-tools:2.4.38")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1") // MethodSplitter: frame types at split points
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "3g"
    // Opt-in live tests: -Pcs.live=true (downloads and EXECUTES third-party plugin code).
    systemProperty("cs.live", providers.gradleProperty("cs.live").orElse("false").get())
    systemProperty("cs.live.names", providers.gradleProperty("cs.live.names").orElse("").get())
    systemProperty("cs.live.repos", providers.gradleProperty("cs.live.repos").orElse("").get())
    testLogging { showStandardStreams = true }
}

// Diagnostics: dex2jar output WITHOUT post-processing.  ./gradlew :plugin-loader:convertRaw -Pcs3=<in.cs3> -Pout=<out.jar>
tasks.register<JavaExec>("convertRaw") {
    mainClass.set("cloudstream.desktop.loader.ConverterMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "3g"
    doFirst { args = listOf(project.property("cs3") as String, project.property("out") as String) }
}

// Safety scan of the installed extensions (bytecode only, nothing runs).  ./gradlew :plugin-loader:scanPlugins [-Pdir=<plugins dir>]
tasks.register<JavaExec>("scanPlugins") {
    mainClass.set("cloudstream.desktop.loader.ScanMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    doFirst { args = listOf((project.findProperty("dir") as String?).orEmpty()) }
}

// Diagnostics: split oversized methods of one class.  ./gradlew :plugin-loader:splitProbe -Pjar=<raw.jar> -Pcls=<a/b/C>
tasks.register<JavaExec>("splitProbe") {
    mainClass.set("cloudstream.desktop.loader.SplitProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "3g"
    doFirst { args = listOf(project.property("jar") as String, project.property("cls") as String) }
}
