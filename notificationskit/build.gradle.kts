plugins {
    id("maven-publish")
}

repositories {
    google()
    mavenCentral()
}

// Dummy assemble task (needed for JitPack) — mirrors appskitsdk-android/aks/build.gradle.kts.
// There's nothing to compile here: this repo carries no real source, only the prebuilt AAR
// checked into libs/. JitPack clones this repo and runs `publishToMavenLocal` (see ../jitpack.yml),
// which just re-packages that binary as a Maven artifact.
tasks.register("assemble") {
    group = "build"
    description = "Dummy assemble task for JitPack"
    doLast {
        println("Assemble task: nothing to build, only publishing the AAR.")
    }
}

// Bump `version` (and the artifact filename below) every time a new build of the real
// notifications-kit source is dropped into libs/ — see this repo's README for the full
// build → copy → tag → publish steps.
publishing {
    publications {
        create<MavenPublication>("NotificationsKit") {
            groupId = "com.github.Pentabit-Labs-LLC"
            artifactId = "notificationskit"
            version = "0.2.1.1"
            artifact("$projectDir/libs/NotificationsKit_v0211.aar")
        }
    }
}
