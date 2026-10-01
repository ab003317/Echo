import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val signingFile = rootProject.file("signing.properties")
val signingValues = Properties().apply { if(signingFile.exists()) signingFile.inputStream().use { load(it) } }
android {
    namespace = "org.dddd010010.serein"
    compileSdk = 36
    defaultConfig { applicationId = "org.dddd010010.serein"; minSdk = 26; targetSdk = 36; versionCode = 13; versionName = "0.9.1"; resourceConfigurations += listOf("en", "zh-rTW", "b+zh+Hant") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    signingConfigs { if(signingFile.exists()) create("serein") {
        storeFile = rootProject.file(signingValues.getProperty("storeFile"))
        storePassword = signingValues.getProperty("storePassword")
        keyAlias = signingValues.getProperty("keyAlias")
        keyPassword = signingValues.getProperty("keyPassword")
    } }
    buildTypes { getByName("release") {
        if(signingFile.exists()) signingConfig = signingConfigs.getByName("serein")
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
}
