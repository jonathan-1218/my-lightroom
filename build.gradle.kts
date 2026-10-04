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
}
