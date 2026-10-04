plugins { id("com.android.application") version "9.2.1" }

android {
    namespace = "dev.lumen"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.lumen"
        minSdk = 33 // RuntimeShader (GPU shaders) needs Android 13+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true // R8: strips unused Kotlin stdlib, keeps the APK small
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // ponytail: personal use, so release is signed with the local debug key; make a real keystore before sharing the APK
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
