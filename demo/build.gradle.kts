plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fetch.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fetch.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":service"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)

    // The harness constructs the engine directly, so it supplies the driver.
    implementation(libs.sqlite.androidx)
    implementation(libs.sqlite.bundled)
}
