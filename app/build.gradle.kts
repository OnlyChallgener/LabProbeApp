plugins {
    id("com.android.application")
}

android {
    namespace = "com.labprobe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.labprobe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
