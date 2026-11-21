import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Google API key comes from local.properties (git-ignored) or the MAPS_API_KEY env var.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mapsApiKey: String =
    localProps.getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: "MISSING_API_KEY"

android {
    namespace = "com.motonav.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.motonav.prototype"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the Navigation SDK (its AAR metadata asks for core library desugaring).
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Google Navigation SDK. 7.0.0 needs AGP >= 8.2.0. Newer releases (8.x) need AGP >= 9.0.1;
    // upgrade the plugin in the root build.gradle.kts first if you want them.
    // NOTE: the Navigation SDK cannot be combined with the Maps SDK in the same app.
    implementation("com.google.android.libraries.navigation:navigation:7.0.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")

    // A real org.json for local unit tests: the one in the Android stub jar returns empty values.
    // Only the route parser tests (route/OsrmRouteParserTest) need it; the app itself uses the platform's.
    testImplementation("org.json:json:20240303")
}
