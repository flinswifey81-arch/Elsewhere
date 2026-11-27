plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.example.phonelab"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
        versionCode = 1
        versionName = "0.1-lab"
    }
}


dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.robolectric)
}
