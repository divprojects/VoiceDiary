import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}
val releaseStoreFile = signingProperties.getProperty("storeFile")
val releaseKeyAlias = signingProperties.getProperty("keyAlias")
val releaseStorePassword = signingProperties.getProperty("storePassword")
val releaseKeyPassword = signingProperties.getProperty("keyPassword")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword
).all { !it.isNullOrBlank() } &&
    releaseStoreFile?.let { rootProject.file(it).isFile } == true

android {
    namespace = "com.voicediary.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voicediary.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.10.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        // API 37 and core-ktx 1.19.1 require a newer preview toolchain than this app supports.
        disable += setOf("OldTargetApi", "GradleDependency")
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        doFirst {
            check(releaseSigningConfigured) {
                "Release signing is not configured. Create keystore.properties with a private release keystore before building a release."
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
