plugins { application }

// Executes third-party plugin code and makes live network requests. NEVER wired into build/test/CI;
// run explicitly:  ./gradlew --no-configuration-cache :tools:compat-harness:run --args="..."
dependencies {
    // ORDER MATTERS: shadowing modules must precede library-jvm on the runtime classpath.
    implementation(project(":android-shim"))
    implementation(project(":webview-desktop"))
    implementation(project(":storage"))
    implementation(project(":plugin-loader"))
    implementation(project(":core"))
    implementation(project(":contracts"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("cloudstream.desktop.harness.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx1g")
}

tasks.named<JavaExec>("run") {
    // Relative paths in --args (reports/, tools/compat-harness/...) resolve against the repo root.
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
