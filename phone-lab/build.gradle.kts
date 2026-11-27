plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.phonelab"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
        }
    }
    defaultConfig {
        applicationId = "com.aistudio.elsewhere.abwzy.phonelab"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-lab"
    }
}


dependencies {
    implementation(project(":phone-core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.robolectric)
}
