import java.util.Properties

plugins {
    id("com.android.application") version "8.6.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

// Release signing: ryzeapp/keystore.properties (storeFile, storePassword, keyAlias, keyPassword) points at the
// keystore; both are git-ignored (see docs/APP.md "Release build"). Without the file the release build is unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "au.buzz.ryzewave"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.buzz.ryzewave"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = "" }
        getByName("release") {
            // Deliberately debuggable (Buzz, 2026-09-06). These APKs are sideloaded from GitHub, never sold or
            // put on the Play Store, and this is the only way the owner of the phone can get at their own data:
            // it re-enables `adb shell run-as au.buzz.ryzewave` (and so tools/pull_app_data.sh) on a release
            // build. `allowBackup` stays false on purpose, so nothing is uploaded to Google's cloud backup.
            // The cost: anyone with USB debugging enabled and an authorised computer can read the app's data,
            // and the Play Store would reject the APK. Do not "fix" this without asking Buzz.
            isDebuggable = true
            isMinifyEnabled = false       // no shrinking yet; turn on together with proguard-rules.pro
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // JVM unit tests run classes that call android.util.Log (the Health Connect exporter): stub it out instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // The app has no fragments, but play-services-location drags in androidx.fragment 1.0.0, and the release build's
    // lintVital treats that next to registerForActivityResult as fatal (InvalidFragmentVersionForActivityResult).
    constraints {
        implementation("androidx.fragment:fragment:1.6.2") { because("lintVitalRelease: Fragment < 1.3.0 cannot use the activity-result API") }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
