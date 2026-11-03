plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.unta.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.unta.app"
        minSdk = 29
        targetSdk = 34
        // номер сборки приходит из CI (-PbuildNumber=12), версия показывается как 1.2
        val bn = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = bn
        versionName = "${bn / 10}.${bn % 10}"
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("unta.keystore")
            storeType = "pkcs12"
            storePassword = "untapublic"
            keyAlias = "unta"
            keyPassword = "untapublic"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
