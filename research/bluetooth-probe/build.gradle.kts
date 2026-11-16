plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}
android {
    namespace = "com.bcardo.ringcoach.bleresearch"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.bcardo.ringcoach.bleresearch"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.test:runner:1.7.0")
    implementation("junit:junit:4.13.2")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.tracing:tracing:1.2.0")
}
