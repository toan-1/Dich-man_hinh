plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
base {
    archivesName.set("DichManhinh")
}
android {
    namespace = "com.example.gametranslator"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.gametranslator"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
