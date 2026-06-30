import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String =
    localProperties.getProperty(name, System.getenv(name).orEmpty())

fun quotedBuildConfigValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.fwp.doubaonewline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fwp.doubaonewline"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.7.0"

        buildConfigField("String", "V2_APP_ID", quotedBuildConfigValue(localProperty("V2_APP_ID")))
        buildConfigField("String", "V2_APP_KEY", quotedBuildConfigValue(localProperty("V2_APP_KEY")))
        buildConfigField(
            "String",
            "V2_ACCESS_TOKEN",
            quotedBuildConfigValue(localProperty("V2_ACCESS_TOKEN"))
        )
        buildConfigField(
            "String",
            "V2_RESOURCE_ID",
            quotedBuildConfigValue(
                localProperty("V2_RESOURCE_ID").ifBlank { "volc.speech.dialog" }
            )
        )
        buildConfigField(
            "double",
            "V2_PRICE_PER_MILLION_TOKENS",
            localProperty("V2_PRICE_PER_MILLION_TOKENS").toDoubleOrNull()?.toString() ?: "0.0"
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.bytedance.speechengine:speechengine_tob:0.0.14.6.1-bugfix")
    implementation("com.squareup.okhttp3:okhttp:4.9.1")
    testImplementation("junit:junit:4.13.2")
}
