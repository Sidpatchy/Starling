import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop")

    val xcf = XCFramework()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            xcf.add(this)
        }
    }

    android {
        namespace = "dev.starling.shared"
        compileSdk = 37
        minSdk = 24
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        val commonTest = getByName("commonTest")
        val iosMain = create("iosMain") {
            dependsOn(commonMain)
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val iosTest = create("iosTest") {
            dependsOn(commonTest)
        }
        getByName("iosArm64Test").dependsOn(iosTest)
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)

        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
        }

    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.starling.generated.resources"
}

compose.desktop {
    application {
        mainClass = "dev.starling.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Starling"
            packageVersion = "0.1.0"

            linux {
                iconFile.set(project.file("../assets/branding/starling-light-preview.png"))
            }

            windows {
                iconFile.set(project.file("../assets/branding/starling.ico"))
                upgradeUuid = "db0d573c-06b1-4e8a-9e18-2021a8c9ef71"
            }

            macOS {
                bundleID = "dev.starling.desktop"
                iconFile.set(project.file("../assets/branding/starling.icns"))
            }
        }
    }
}

tasks.named<ProcessResources>("desktopProcessResources") {
    from("src/main/python") {
        into("python")
    }
}
