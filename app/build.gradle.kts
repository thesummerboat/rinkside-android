plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from gradle properties so CI can set it from the git tag:
//   ./gradlew assembleRelease -PversionName=0.1.0 -PversionCode=1000
val appVersionName: String = (findProperty("versionName") as? String)?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"
val appVersionCode: Int = (findProperty("versionCode") as? String)?.toIntOrNull() ?: 1

// The only server the app talks to. Change it here (or build with -PrinksideBaseUrl=https://example.org).
val rinksideBaseUrl: String = ((findProperty("rinksideBaseUrl") as? String)?.takeIf { it.isNotBlank() }
    ?: "https://rinkside.jonaspettersson1991-18b.workers.dev").trimEnd('/')

android {
    namespace = "se.jonasp.rinkside"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.jonasp.rinkside"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BASE_URL", "\"$rinksideBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // The release key comes from environment variables (set by the GitHub Actions release job).
        // Without ANDROID_KEYSTORE_PATH nothing is configured here, so a release build is left UNSIGNED
        // (never debug-signed); the workflow refuses to publish in that case.
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // No lint gate in CI: the build must not fail on a lint opinion.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // The app itself has no third-party dependencies at all (framework APIs only).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
