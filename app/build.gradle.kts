plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val releaseStoreFilePath = System.getenv("RELEASE_STORE_FILE")
    ?: project.findProperty("RELEASE_STORE_FILE") as String?
val releaseStoreFile = releaseStoreFilePath?.let { file(it) }?.takeIf { it.isFile }

// Tag builds pass -PVERSION_NAME=0.9.1 (from v0.9.1). Local / untagged CI keeps the fallback.
val versionNameOverride = gradleString("VERSION_NAME")?.removePrefix("v")
val appVersionName = versionNameOverride ?: "0.8.0"
val appVersionCode = gradleString("VERSION_CODE")?.toIntOrNull()
    ?: versionNameOverride?.let(::versionCodeFromName)
    ?: 8
logger.lifecycle("App version $appVersionName ($appVersionCode)")

android {
    namespace = "ch.steigis.overprint"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.steigis.overprint"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = releaseStoreFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    ?: project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enableAndroidTest = false
    }
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("overprint.apk")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // Secrets are encrypted with an Android Keystore key directly (see SecretCipher),
    // so androidx.security:security-crypto is not needed.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.google.truth:truth:1.4.4")
}

private fun gradleString(name: String): String? =
    findProperty(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

/** 0.9.1 → 901, 1.2.3 → 10203. Parts must be 0..99. */
private fun versionCodeFromName(name: String): Int {
    val parts = name.substringBefore("-").split('.')
    require(parts.size in 2..3) { "VERSION_NAME must be like 0.9.1, got '$name'" }
    val major = parts[0].toIntOrNull() ?: error("Invalid VERSION_NAME: $name")
    val minor = parts[1].toIntOrNull() ?: error("Invalid VERSION_NAME: $name")
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    require(major in 0..99 && minor in 0..99 && patch in 0..99) {
        "VERSION_NAME parts must be 0..99: $name"
    }
    return major * 10000 + minor * 100 + patch
}
