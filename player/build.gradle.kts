plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "io.max.paperplay.player"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm()
    sourceSets {
        commonMain.dependencies { api(libs.coroutines.core) }
        commonTest.dependencies { implementation(kotlin("test")) }
        val javaSrc = "src/javaMain/kotlin"
        androidMain {
            kotlin.srcDir(javaSrc)
            dependencies {
                api(libs.media3.exoplayer)
                api(libs.media3.session)
            }
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
        }
        jvmMain {
            kotlin.srcDir(javaSrc)
            dependencies {
                implementation(libs.vlcj)
                implementation(libs.jna)
            }
        }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}
