plugins {
    id("com.android.application")
}

val accountApiUrl = providers.gradleProperty("ROADCONQUEST_ACCOUNT_API_URL").getOrElse(
    "https://br-withered-dew-b7u432kv-roadconquest.compute.c-13.us-east-1.aws.neon.tech"
)
val osrmApiUrl = providers.gradleProperty("ROADCONQUEST_OSRM_API_URL").getOrElse(
    "https://router.project-osrm.org"
)
val placeOverlayConfigUrl = providers.gradleProperty("ROADCONQUEST_PLACE_OVERLAY_CONFIG_URL").getOrElse(
    "https://raw.githubusercontent.com/AntnyGamer/RoadConquest-Beta/main/OVERLAY_PROVIDER.txt"
)

android {
    namespace = "com.roadfog.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.roadfog.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 31
        versionName = "1.0-beta.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "120000"
        val escapedAccountApiUrl = accountApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedOsrmApiUrl = osrmApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedPlaceOverlayConfigUrl = placeOverlayConfigUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "ACCOUNT_API_URL", "\"$escapedAccountApiUrl\"")
        buildConfigField("String", "OSRM_API_URL", "\"$escapedOsrmApiUrl\"")
        buildConfigField("String", "PLACE_OVERLAY_CONFIG_URL", "\"$escapedPlaceOverlayConfigUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Favor predictable native/SDK behavior for this prototype over APK-size shrinking.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    // Isolate Robolectric's legacy UI, native Canvas and SQLite backends on every host.
    // Sharing their native runtime can corrupt JNI bindings on Windows and Linux.
    forkEvery = 1
    doFirst {
        require(javaLauncher.get().metadata.languageVersion.asInt() >= 21) {
            "The Android 17 Robolectric tests require JDK 21 or newer. Set Android Studio's Gradle JDK or JAVA_HOME accordingly."
        }
    }
    // Robolectric's Android 17 shared-memory interceptor accesses file-descriptor internals.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}

dependencies {
    implementation("com.google.android.play:integrity:1.6.0")
    implementation("androidx.core:core:1.19.1")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
