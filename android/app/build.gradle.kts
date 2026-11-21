plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ruimdetijd.aircav"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.ruimdetijd.aircav"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with your own release keystore (kept OUTSIDE the repo), e.g.:
            //   keytool -genkey -v -keystore ~/aircav-release.jks -keyalg RSA \
            //     -keysize 2048 -validity 10000 -alias aircav
            // then export AIRCAV_KEYSTORE=~/aircav-release.jks
            //      AIRCAV_KEYSTORE_PASSWORD / AIRCAV_KEY_ALIAS / AIRCAV_KEY_PASSWORD
            if (System.getenv("AIRCAV_KEYSTORE") != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(System.getenv("AIRCAV_KEYSTORE"))
                    storePassword = System.getenv("AIRCAV_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("AIRCAV_KEY_ALIAS")
                    keyPassword = System.getenv("AIRCAV_KEY_PASSWORD")
                }
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
