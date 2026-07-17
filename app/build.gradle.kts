plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deepgaze.glasses"  // Your package name
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deepgaze.glasses"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")  // Required for TextInputLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("org.jetbrains.kotlinx:dataframe:0.15.0")

    // KMath for numerical computing
    implementation("space.kscience:kmath-core:0.5.0")
    implementation("space.kscience:kmath-commons:0.5.0")
    implementation("space.kscience:kmath-stat:0.5.0")

    // For Butterworth filter implementation (using Apache Commons Math)
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Kandy plotting library
    implementation("org.jetbrains.kotlinx:kandy-lets-plot:0.8.4") {
        exclude(group = "org.apache.poi")
        exclude(group = "org.apache.commons", module = "commons-math3")
    }

    implementation("androidx.appcompat:appcompat:1.7.0")

    // Lifecycle for coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
}