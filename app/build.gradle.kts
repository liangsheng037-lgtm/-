plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

buildDir = file("${rootDir}/.trae-build/app2")

android {
    namespace = "com.example.demo"
    compileSdk = 36
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val apkId = (project.findProperty("APK_ID") as String?)?.trim().orEmpty()
        val agentCode = (project.findProperty("AGENT_CODE") as String?)?.trim().orEmpty()
        val appId = (project.findProperty("APPLICATION_ID") as String?)?.trim().orEmpty()

        applicationId = if (appId.isNotBlank()) appId else "com.example.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APK_ID", "\"$apkId\"")
        buildConfigField("String", "AGENT_CODE", "\"$agentCode\"")
    }

    val keystoreFile = (project.findProperty("KEYSTORE_FILE") as String?)?.trim().orEmpty()
    val keystorePassword = (project.findProperty("KEYSTORE_PASSWORD") as String?)?.trim().orEmpty()
    val keyAlias = (project.findProperty("KEY_ALIAS") as String?)?.trim().orEmpty()
    val keyPassword = (project.findProperty("KEY_PASSWORD") as String?)?.trim().orEmpty()
    val hasReleaseSigning =
        keystoreFile.isNotBlank() &&
            keystorePassword.isNotBlank() &&
            keyAlias.isNotBlank() &&
            keyPassword.isNotBlank() &&
            file(keystoreFile).exists()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
