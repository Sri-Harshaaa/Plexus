import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    id("com.google.devtools.ksp") version "2.3.10"
    id("com.google.gms.google-services")
}

val plexusLocalProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val plexusWebSocketUrl = providers.gradleProperty("PLEXUS_WEBSOCKET_URL")
    .orElse(providers.environmentVariable("PLEXUS_WEBSOCKET_URL"))
    .orNull
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf { it.isNotEmpty() }
    ?: plexusLocalProperties.getProperty("PLEXUS_WEBSOCKET_URL", "").trim().trimEnd('/')
require(plexusWebSocketUrl.isEmpty() || (plexusWebSocketUrl.startsWith("wss://") &&
    plexusWebSocketUrl.none { it == '\"' || it == '\\' || it.isWhitespace() })) {
    "PLEXUS_WEBSOCKET_URL must be a valid secure WebSocket URL"
}
val releaseStoreFile = providers.gradleProperty("PLEXUS_RELEASE_STORE_FILE")
val releaseStorePassword = providers.gradleProperty("PLEXUS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("PLEXUS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("PLEXUS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it.isPresent }

android {

    namespace = "com.plexus.app"


    compileSdk {
        version = release(37)
    }


    defaultConfig {

        applicationId = "com.plexus.app"

        minSdk = 31
        targetSdk = 37

        versionCode = 1
        versionName = "1.0.0-beta.1"
        buildConfigField("String", "PLEXUS_WEBSOCKET_URL", "\"$plexusWebSocketUrl\"")

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
   }
 signingConfigs {
    if (hasReleaseSigning) {
        create("release") {
            storeFile = file(releaseStoreFile.get())
            storePassword = releaseStorePassword.get()
            keyAlias = releaseKeyAlias.get()
            keyPassword = releaseKeyPassword.get()
        }
    }
}
 buildTypes {

        release {
	   if (hasReleaseSigning) {
    signingConfig = signingConfigs.getByName("release")
           }
            optimization {
                enable = true
            }
        }
    }


    compileOptions {

        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11


        /*
         * Required by Nearby 19.4.0.
         */
        isCoreLibraryDesugaringEnabled = true
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }
}
abstract class VerifyWebSocketUrlTask : DefaultTask() {
    @get:Input
    abstract val webSocketUrl: Property<String>

    @TaskAction
    fun verify() {
        require(webSocketUrl.get().isNotBlank()) {
            "PLEXUS_WEBSOCKET_URL must be set for release builds"
        }
    }
}

val verifyReleaseWebSocketUrl =
    tasks.register<VerifyWebSocketUrlTask>("verifyReleaseWebSocketUrl") {
        webSocketUrl.set(plexusWebSocketUrl)
    }

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseWebSocketUrl)
}
dependencies {


    /*
     * CORE LIBRARY DESUGARING
     *
     * Required by play-services-nearby 19.4.0.
     */
    coreLibraryDesugaring(
        "com.android.tools:desugar_jdk_libs:2.1.5"
    )


    /*
     * COMPOSE
     */
    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )


    /*
     * QR GENERATION
     */
    implementation(
        "com.google.zxing:core:3.5.3"
    )


    /*
     * QR SCANNING
     */
    implementation(
        "com.google.mlkit:barcode-scanning:17.3.0"
    )


    /*
     * CAMERA
     */
    implementation(
        "androidx.camera:camera-camera2:1.6.2"
    )

    implementation(
        "androidx.camera:camera-lifecycle:1.6.2"
    )

    implementation(
        "androidx.camera:camera-view:1.6.2"
    )


    /*
     * NEARBY CONNECTIONS
     *
     * Keep 19.4.0 because 19.5.0 currently
     * requires newer Kotlin metadata than
     * our project compiler understands.
     */
    implementation(
        "com.google.android.gms:play-services-nearby:19.4.0"
    )


    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16.1")

    /*
    * FIREBASE CLOUD MESSAGING
    */
    implementation(
        platform("com.google.firebase:firebase-bom:34.19.0")
    )

    implementation(
        "com.google.firebase:firebase-messaging"
    )
    implementation("androidx.work:work-runtime-ktx:2.10.3")

    /*
     * ROOM DATABASE
     */
    val roomVersion =
        "2.8.5"


    implementation(
        "androidx.room:room-runtime:$roomVersion"
    )

    implementation(
        "androidx.room:room-ktx:$roomVersion"
    )

    ksp(
        "androidx.room:room-compiler:$roomVersion"
    )


    /*
     * ANDROID / COMPOSE
     */
    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )


    /*
     * TESTING
     */
    testImplementation(
        libs.junit
    )
    // Real JSON implementation for protocol unit tests; Android supplies it at runtime.
    testImplementation("org.json:json:20240303")


    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )


    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}
