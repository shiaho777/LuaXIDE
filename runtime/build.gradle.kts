plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.webviewide.runtime"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.webviewide.runtime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
}

tasks.register<Copy>("syncRuntimeTemplate") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(rootProject.file("app/src/main/assets/runtime"))
    rename { "template.apk" }
    doLast {
        println("synced runtime template -> app/src/main/assets/runtime/template.apk")
    }
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        finalizedBy("syncRuntimeTemplate")
    }
}
