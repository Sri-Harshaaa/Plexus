plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    id("com.google.devtools.ksp") version "2.3.10"
}


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
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {

        release {

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
    }
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


    /*
     * SOCKET.IO INTERNET TRANSPORT
     *
     * Java client 2.x is compatible with
     * Socket.IO server 3.x / 4.x.
     *
     * org.json is excluded because Android
     * already provides it.
     */
    implementation(
        "io.socket:socket.io-client:2.1.2"
    ) {
        exclude(
            group = "org.json",
            module = "json"
        )
    }


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
