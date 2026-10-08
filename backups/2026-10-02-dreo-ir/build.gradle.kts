plugins { id("com.android.application") }
layout.buildDirectory.set(file("out-sensors"))
android {
    namespace = "com.aaron.breeze"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aaron.breeze"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.aaron.breeze.StoreTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { testImplementation("junit:junit:4.13.2") }
