plugins {
    id("com.android.application")
}

android {
    namespace = "io.layaedge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.layaedge.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
