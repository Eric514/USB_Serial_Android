import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deepgaze.glasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deepgaze.glasses"
        minSdk = 26
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

    // UPDATED: Add more exclusions for common duplicate files
    packaging {
        resources {
            // Exclude duplicate Jupyter metadata
            excludes += "META-INF/kotlin-jupyter-libraries/libraries.json"

            // Exclude duplicate license files
            excludes += "META-INF/thirdparty-LICENSE"
            excludes += "META-INF/thirdparty-LICENSE.txt"
            excludes += "META-INF/thirdparty-NOTICE"
            excludes += "META-INF/thirdparty-NOTICE.txt"

            // Exclude other common Kotlin metadata files
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/*.version"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // KMath for numerical computing
    implementation("space.kscience:kmath-core:0.5.0")
    implementation("space.kscience:kmath-commons:0.5.0")
    implementation("space.kscience:kmath-stat:0.5.0")

    // For Butterworth filter implementation (using Apache Commons Math)
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Kandy plotting library
    implementation("org.jetbrains.kotlinx:kandy-lets-plot:0.8.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}