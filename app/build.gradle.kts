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
        versionCode = 2
        versionName = "0.2.0"
    }
}

dependencies {
    implementation("com.jcraft:jsch:0.1.55")
}
