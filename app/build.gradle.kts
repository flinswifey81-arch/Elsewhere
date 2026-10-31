plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

val legacyApplicationId = "com.aistudio.elsewhere.abwzy"
val productionApplicationId = "com.aistudio.elsewhere.abwzy.v15"
val testSigningValues = mapOf(
  "path" to System.getenv("ELSEWHERE_TEST_KEYSTORE_PATH"),
  "storePassword" to System.getenv("ELSEWHERE_TEST_STORE_PASSWORD"),
  "keyAlias" to System.getenv("ELSEWHERE_TEST_KEY_ALIAS"),
  "keyPassword" to System.getenv("ELSEWHERE_TEST_KEY_PASSWORD")
)
val hasCompleteTestSigningConfiguration = testSigningValues.values.all { !it.isNullOrBlank() }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = productionApplicationId
    minSdk = 33
    targetSdk = 36
    versionCode = 1
    versionName = "1.5"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField(
      "String",
      "BACKUP_IMPORT_SOURCE_APPLICATION_ID",
      "\"$legacyApplicationId\""
    )
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    if (hasCompleteTestSigningConfiguration) {
      create("testing") {
        storeFile = file(requireNotNull(testSigningValues.getValue("path")))
        storePassword = testSigningValues.getValue("storePassword")
        keyAlias = testSigningValues.getValue("keyAlias")
        keyPassword = testSigningValues.getValue("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { }
    create("sandbox") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".test"
      versionNameSuffix = "-test"
      buildConfigField(
        "String",
        "BACKUP_IMPORT_SOURCE_APPLICATION_ID",
        "\"$productionApplicationId\""
      )
      matchingFallbacks += listOf("debug")
      signingConfigs.findByName("testing")?.let { signingConfig = it }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

androidComponents {
  beforeVariants(selector().withBuildType("sandbox")) { variantBuilder ->
    (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
  }
  onVariants(selector().withBuildType("sandbox")) { variant ->
    // 4 KB-aligned, uncompressed .so entries can be rejected by 16 KB page-size devices.
    // Compress/extract sandbox native libraries; leave production APK packaging unchanged.
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  add("sandboxImplementation", libs.androidx.compose.ui.test.manifest)
  add("sandboxImplementation", libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
