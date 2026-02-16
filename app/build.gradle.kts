plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.ft_file_manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ft_file_manager"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            // Αυτό λέει στο Gradle να αγνοήσει τα διπλότυπα αρχεία MANIFEST.MF
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"

            // Προληπτικά, μπορείς να προσθέσεις και αυτά αν σου βγάλει κι άλλα λάθη
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.identity.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // ΑΠΑΡΑΙΤΗΤΑ για το UI που φτιάξαμε (XML/Views)
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // FTP Library
    implementation("commons-net:commons-net:3.9.0")

    implementation("com.github.bumptech.glide:glide:5.0.5")

    // Για SMB (Samba/Windows Share)
    implementation("com.hierynomus:smbj:0.14.0")

    // Για SFTP (SSH)
    implementation("com.jcraft:jsch:0.1.55")

    implementation("com.rapid7.client:dcerpc:0.12.13")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("com.hierynomus:asn-one:0.6.0")

    implementation("org.codelibs:jcifs:3.0.2")
}