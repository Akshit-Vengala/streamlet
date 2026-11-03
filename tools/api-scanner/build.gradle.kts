plugins { application }

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    testImplementation(kotlin("test"))
}

application { mainClass.set("cloudstream.desktop.scanner.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
