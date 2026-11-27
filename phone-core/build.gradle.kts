plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.example.phonecore"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests {
        isIncludeAndroidResources = true
        all { it.systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2") }
    } }
}
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.robolectric)
}
