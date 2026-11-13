plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "ru.dmitriy.workouts"
    compileSdk = 37
    defaultConfig {
        applicationId = "ru.dmitriy.workouts"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.1.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.preview); implementation(libs.compose.foundation)
    implementation(libs.compose.material3); implementation(libs.compose.icons)
    implementation(libs.activity); implementation(libs.core)
    implementation(libs.lifecycle.compose); implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.runtime); implementation(libs.navigation.ui)
    implementation(libs.hilt.android); implementation(libs.hilt.compose); ksp(libs.hilt.compiler)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.datastore); implementation(libs.coroutines); implementation(libs.serialization); implementation(libs.okhttp)
    debugImplementation(libs.compose.tooling); debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test); androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.room.testing); androidTestImplementation(libs.compose.test)
    // Official API 37 InputManager fix; confined to test APK, never shipped in the app.
    androidTestImplementation(libs.espresso)
}
