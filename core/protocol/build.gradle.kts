plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    api(project(":core:transport"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Local-only recorded sessions for RecordedLogConformanceTest (issue #7).
// The directory is git-ignored and usually absent; the suite skips cleanly.
val localCanlogDir = rootDir.resolve("logs")
tasks.withType<Test>().configureEach {
    systemProperty("ooc.canlogDir", localCanlogDir.absolutePath)
    inputs.files(fileTree(localCanlogDir) { include("*.canlog") })
        .withPropertyName("localCanlogs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
