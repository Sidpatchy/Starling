plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.starling"
    compileSdk = 37

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    defaultConfig {
        applicationId = "dev.starling"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets.getByName("main").assets.directories.add(rootProject.file("licenses").path)
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("rns==1.5.7")
            install("lxmf==1.2.0")
        }
    }
    sourceSets {
        getByName("main") {
            setSrcDirs(listOf("src/main/python", "../composeApp/src/main/python"))
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
