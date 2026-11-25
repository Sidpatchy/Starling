plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.sidpatchy.Starling"
    compileSdk = 37

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    defaultConfig {
        applicationId = "com.sidpatchy.Starling"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("starlingVersionCode").get().toInt()
        versionName = providers.gradleProperty("starlingVersion").get()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets.getByName("main").assets.directories.add(rootProject.file("licenses").path)
}

// Keep this Android-only compatibility patch pinned to the wheel checked by the patch script.
val androidRnsWheel = layout.buildDirectory.file("starling-rns/rns-1.5.7-py3-none-any.whl")
val patchRnsWheelScript = rootProject.layout.projectDirectory.file("packaging/android/patch_rns_wheel.py")
val prepareAndroidRnsWheel = tasks.register<Exec>("prepareAndroidRnsWheel") {
    inputs.file(patchRnsWheelScript)
    outputs.file(androidRnsWheel)
    commandLine("python3", patchRnsWheelScript.asFile.absolutePath, androidRnsWheel.get().asFile.absolutePath)
}

tasks.matching { it.name.startsWith("install") && it.name.endsWith("PythonRequirements") }.configureEach {
    dependsOn(prepareAndroidRnsWheel)
    inputs.file(androidRnsWheel)
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install(androidRnsWheel.get().asFile.absolutePath)
            install("lxmf==1.2.0")
            // BLE peer protocol (MIT). Core package only: the Linux driver's
            // optional dependencies are not installed and Starling supplies its own driver.
            install("git+https://github.com/torlando-tech/ble-reticulum.git@63557acfe5c923dcdafeb7bc37508bf835f16514")
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
