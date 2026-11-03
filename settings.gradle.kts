rootProject.name = "cloudstream-desktop"

pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}

// Upstream CloudStream (pinned submodule). Only its `library` module is consumed.
includeBuild("upstream") {
    dependencySubstitution {
        substitute(module("com.lagradost.api:library")).using(project(":library"))
    }
}

include(
    ":contracts",
    ":core", ":plugin-loader", ":android-shim", ":webview-desktop",
    ":player", ":storage", ":desktop-app", ":packaging", ":integration",
    ":tools:api-scanner", ":tools:compat-harness",
)
