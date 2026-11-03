plugins { application }

dependencies {
    // ORDER MATTERS: shadowing modules must precede library-jvm on the runtime classpath.
    implementation(project(":android-shim"))
    implementation(project(":webview-desktop"))
    implementation(project(":storage"))
    implementation(project(":plugin-loader"))
    implementation(project(":player"))
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jna) // WindowClickProbe: which window is in front
    compileOnly("com.github.Blatzar:NiceHttp:0.4.18") // probe calls `app` directly; runtime copy comes via library-jvm
}

application {
    mainClass.set("cloudstream.desktop.integration.IntegrationSmokeKt")
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}

// libmpv software-render check (no window).  ./gradlew :integration:renderSmoke -Psrc=<file|url> -Pout=<dir>
tasks.register<JavaExec>("renderSmoke") {
    group = "verification"
    mainClass.set("cloudstream.desktop.integration.RenderSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("cloudstream.mpv.dll", rootProject.file("player/native/libmpv-2.dll").absolutePath)
    doFirst { args = listOfNotNull(project.property("src") as String, project.property("out") as String, project.findProperty("srt") as String?) }
}

// Browse-only probe: mainPage/search/load with full stack traces.
// ./gradlew :integration:probe -Pcs3=<file.cs3> -Pwork=<dir> -Pquery=naruto [-Phits=3]
tasks.register<JavaExec>("probe") {
    group = "verification"
    mainClass.set("cloudstream.desktop.integration.LoadProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx3g", "--enable-native-access=ALL-UNNAMED")
    doFirst {
        args = listOf(project.property("cs3") as String, project.property("work") as String, project.property("query") as String,
            (project.findProperty("hits") as String?) ?: "3")
    }
}

// Does a click reach the page in the verification window? (opens a window, moves the mouse)  ./gradlew :integration:windowClick
tasks.register<JavaExec>("windowClick") {
    group = "verification"
    mainClass.set("cloudstream.desktop.integration.WindowClickProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    doFirst { project.findProperty("jcefArgs")?.let { systemProperty("cloudstream.jcef.args", it as String) } }
}

// Network probe of one link family (no playback).  ./gradlew :integration:linkProbe -Pcs3=<file.cs3> -Pwork=<dir> -Pquery=Inception -Pfilter=<source name>
tasks.register<JavaExec>("linkProbe") {
    group = "verification"
    mainClass.set("cloudstream.desktop.integration.LinkProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx3g", "--enable-native-access=ALL-UNNAMED")
    doFirst {
        val a = mutableListOf(project.property("cs3") as String, project.property("work") as String, project.property("query") as String, project.property("filter") as String)
        project.findProperty("index")?.let { a += listOf("--index", it as String) }
        if (project.findProperty("webviewDebug") != null) systemProperty("cloudstream.webview.debug", "true")
        args = a
    }
}

// Plays a real stream through embedded libmpv and screenshots it (runs plugin code, network, opens a window).
// ./gradlew :integration:play -Pcs3=<file.cs3> -Pwork=<dir> -Pquery=naruto [-PlinkIndex=0]
tasks.register<JavaExec>("play") {
    group = "verification"
    mainClass.set("cloudstream.desktop.integration.PlaySmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx3g", "--enable-native-access=ALL-UNNAMED")
    doFirst {
        project.findProperty("mpvLog")?.let { systemProperty("cloudstream.mpv.log", it as String) }
        val a = mutableListOf(project.property("cs3") as String, project.property("work") as String, project.property("query") as String)
        project.findProperty("linkIndex")?.let { a += listOf("--link-index", it as String) }
        project.findProperty("subLang")?.let { a += listOf("--sub-lang", it as String) }
        project.findProperty("seekSec")?.let { a += listOf("--seek-sec", it as String) }
        if (project.findProperty("controlsTest") != null) a += "--controls-test"
        systemProperty("cloudstream.mpv.dll", rootProject.file("player/native/libmpv-2.dll").absolutePath)
        project.findProperty("splitForce")?.let { systemProperty("cloudstream.split.force", it as String) } // splitter validation
        args = a
    }
}
