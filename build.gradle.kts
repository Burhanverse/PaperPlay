plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.sqldelight) apply false
}

// The app version. CI computes it from the git tags and passes -PappVersion=1.3.0-beta.7 and -PappVersionCode=<commit count>;
// a local build reads version.properties and counts the commits itself, so it never installs below a CI build.
val appVersion: String = (findProperty("appVersion") as String?)
    ?: java.util.Properties().apply { file("version.properties").inputStream().use(::load) }.getProperty("version")
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toInt()
    ?: runCatching { providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt() }.getOrDefault(1)
extra["appVersion"] = appVersion
extra["appVersionCode"] = appVersionCode

// A test that never ends stops its run; on CI the log names it: the last one that started and did not end.
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(15))
        if (System.getenv("CI") != null) testLogging.events("started", "passed", "failed")
    }
}
