plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.example.phonelab"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "com.aistudio.elsewhere.abwzy.phonelab"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-lab"
    }
}
