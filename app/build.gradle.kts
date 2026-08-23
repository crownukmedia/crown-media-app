plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val crownActivationBaseUrl = providers.gradleProperty("crownActivationBaseUrl").orElse("").get()
val crownPremiumUrl = providers.gradleProperty("crownPremiumUrl").orElse("http://novixa.uk:8880").get()
fun quotedBuildConfig(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "uk.crownmedia.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "uk.crownmedia.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ACTIVATION_BASE_URL", quotedBuildConfig(crownActivationBaseUrl))
        buildConfigField("String", "CROWN_PREMIUM_URL", quotedBuildConfig(crownPremiumUrl))
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:design"))
    implementation(project(":domain"))
    implementation(project(":data:xtream"))
    implementation(project(":data:activation"))
    implementation(project(":player"))
    implementation(project(":features:activation"))
    implementation(project(":features:home"))
    implementation(project(":features:live"))
    implementation(project(":features:movies"))
    implementation(project(":features:series"))
    implementation(project(":features:search"))
    implementation(project(":features:account"))
    implementation(project(":features:settings"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.security.crypto)
    implementation(libs.coil.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.work.runtime)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.exoplayer)
    testImplementation(libs.media3.datasource.okhttp)
    testImplementation(libs.okhttp.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
}
