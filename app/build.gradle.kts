plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openclaw.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openclaw.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/beans.xml"
        }
    }
}

dependencies {
    implementation(project(":core-player"))
    implementation(project(":core-protocol"))
    implementation(project(":core-ui"))
    implementation(project(":feature-discovery"))
    implementation(project(":feature-player"))

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("org.fourthline.cling:cling-core:2.1.2")
    implementation("org.fourthline.cling:cling-support:2.1.2")
    implementation("org.eclipse.jetty:jetty-server:8.1.22.v20160922") {
        exclude(group = "org.eclipse.jetty.orbit", module = "javax.servlet")
    }
    implementation("org.eclipse.jetty:jetty-servlet:8.1.22.v20160922") {
        exclude(group = "org.eclipse.jetty.orbit", module = "javax.servlet")
    }
    implementation("org.eclipse.jetty:jetty-client:8.1.22.v20160922")
    implementation("javax.servlet:javax.servlet-api:3.0.1")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
}
