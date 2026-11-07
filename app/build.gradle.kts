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
    // Keep the original Elsewhere Test package stable across production version bumps.
    // Release and debug explicitly retain their v1.5 suffixes below.
    applicationId = legacyApplicationId
    minSdk = 33
    targetSdk = 36
    // In-place update of regular Elsewhere v1.5; preserve app ID, data and signing key.
    // Increment for a safe in-place update from the signed 1.5.1 production candidate.
    versionCode = 3
    versionName = "1.5.2"

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
      applicationIdSuffix = ".v15"
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      applicationIdSuffix = ".v15"
    }
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
    create("worldsPreview") {
      initWith(getByName("sandbox"))
      // Preserve the already-separate Worlds Preview package ID.
      applicationIdSuffix = ".v15.test.worlds"
      versionNameSuffix = "-worlds-preview"
      matchingFallbacks += listOf("sandbox", "debug")
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
  onVariants(selector().withBuildType("release")) { variant ->
    // Keep production native libraries installable on devices using 16 KB pages.
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
  }
  beforeVariants(selector().withBuildType("sandbox")) { variantBuilder ->
    (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
  }
  onVariants(selector().withBuildType("sandbox")) { variant ->
    // Compress/extract sandbox native libraries; leave production APK packaging unchanged.
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
  }
  onVariants(selector().withBuildType("worldsPreview")) { variant ->
    // Worlds Preview is also isolated testing and needs the same 16 KB compatibility.
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
  }
}

// Robolectric/Compose tests build large Android text/layout graphs. Give their
// isolated workers enough heap and periodically restart the worker so an entire
// suite cannot accumulate stale ShadowTrace/layout objects until it OOMs.
// This does not skip or soften any tests.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
  maxHeapSize = "1536m"
  maxParallelForks = 1
  forkEvery = 24
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
