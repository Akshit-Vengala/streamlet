dependencies {
    api(project(":contracts"))
    // BasePlugin, ExtractorApi etc. that the shimmed Plugin/VideoClickAction build on.
    api("com.lagradost.api:library")
    implementation(libs.kotlinx.coroutines.core)
    // Provided by the CloudStream app (app/build.gradle.kts) and used by plugins directly.
    api("com.google.code.gson:gson:2.11.0")
    // @JsonProperty on hand-written app data classes plugins parse JSON into; runtime copy comes from library-jvm (2.13.1 pin).
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.13.1")
    // Dispatchers.Main on desktop (plugins launch work on Main from load()); runs on the Swing EDT.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}

// Generated stubs (tools/gen_stubs.py) live beside the handwritten sources.
sourceSets {
    main {
        java.srcDir("src/generated/java")
    }
}
