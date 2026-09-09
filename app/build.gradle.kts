plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.hankaviator.gmailadremover"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hankaviator.gmailadremover"
        minSdk = 27
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
