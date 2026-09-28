@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.android.build.api.variant.ResValue
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties


plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}
val previewKeystorePropertiesFile = rootProject.file("preview-keystore.properties")
val previewKeystoreProperties = Properties()
if (previewKeystorePropertiesFile.exists()) {
    FileInputStream(previewKeystorePropertiesFile).use(previewKeystoreProperties::load)
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !previewKeystoreProperties.getProperty(it).isNullOrBlank()
    }) { "preview-keystore.properties must define storeFile, storePassword, keyAlias, and keyPassword" }
    if (!keystoreProperties.getProperty("storeFile").isNullOrBlank()) {
        require(rootProject.file(previewKeystoreProperties.getProperty("storeFile")).canonicalFile !=
            file(keystoreProperties.getProperty("storeFile")).canonicalFile) {
            "Preview and Stable must use different keystore files"
        }
    }
    val previewStore = rootProject.file(previewKeystoreProperties.getProperty("storeFile"))
    if (previewStore.exists()) {
        val store = KeyStore.getInstance(previewStore, previewKeystoreProperties.getProperty("storePassword").toCharArray())
        val certificate = requireNotNull(store.getCertificate(previewKeystoreProperties.getProperty("keyAlias"))) {
            "Preview signing alias is missing from its keystore"
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        require(fingerprint != "98de410a5f16c5743ca3885d4ded7850fab73730a99bfd67f5912a5d91f6b736") {
            "Preview must not use the published Stable signing certificate"
        }
    }
}

android {
    namespace = "com.dd3boh.outertune"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dd3boh.outertune"
        minSdk = 24
        targetSdk = 36
        versionCode = 92
        versionName = "0.11.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // expose the TagLib library version (from the version catalog) for the About screen
        buildConfigField("String", "TAGLIB_VERSION", "\"${libs.versions.taglib.get()}\"")
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("ot_release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                (keystoreProperties["keyAlias"] as? String)?.let {
                    keyAlias = it
                }
                (keystoreProperties["keyPassword"] as? String)?.let {
                    keyPassword = it
                }
                (keystoreProperties["storePassword"] as? String)?.let {
                    storePassword = it
                }
            }
        } else {
            create("ot_release") { }
        }
        if (!previewKeystoreProperties.isEmpty) {
            create("preview_release") {
                storeFile = rootProject.file(previewKeystoreProperties.getProperty("storeFile"))
                storePassword = previewKeystoreProperties.getProperty("storePassword")
                keyAlias = previewKeystoreProperties.getProperty("keyAlias")
                keyPassword = previewKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Channel flavors select their own signing keys; absent local properties stay unsigned.
            signingConfig = null
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        // userdebug is release builds without minify
        create("userdebug") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
//            isDebuggable = true
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

// build variants and stuff
    splits {
        abi {
            isEnable = true
            reset()

            include("x86_64", "x86", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    flavorDimensions.addAll(listOf("channel", "abi"))

    productFlavors {
        create("stable") {
            isDefault = true
            dimension = "channel"
            signingConfig = if (keystoreProperties.isEmpty) null else signingConfigs.getByName("ot_release")
        }

        create("preview") {
            dimension = "channel"
            applicationIdSuffix = ".preview"
            signingConfig = if (previewKeystoreProperties.isEmpty) null else signingConfigs.getByName("preview_release")
        }

        // main version
        create("core") {
            isDefault = true
            dimension = "abi"
        }

        // fully featured version, large file size
        create("full") {
            dimension = "abi"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "OuterTune-${variant.versionName}-${output.baseName}-${output.versionCode}.apk"
                output.outputFileName = outputFileName
            }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")

        }
    }

    tasks.withType<KotlinCompile> {
        // Tag extraction uses TagLib in every flavor. Only the FFmpeg playback decoder
        // (nextlib) is flavor-gated: the "full" flavor links it from the prebuilt AAR,
        // while other flavors compile the dud stub.
        if (name.substringAfter("compile").contains("Full")) {
            exclude("**/*ffdecoderDud.kt")
        }
    }


    aboutLibraries {
        offlineMode = true

        collect {
            fetchRemoteLicense = false
            fetchRemoteFunding = false
            filterVariants.addAll("release")
        }

        export {
            // Remove the "generated" timestamp to allow for reproducible builds
            excludeFields = listOf("generated")
        }

        license {
            // Define the strict mode, will fail if the project uses licenses not allowed
            strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
            // Allowed set of licenses, this project will be able to use without build failure
            allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause", "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1", "GNU GENERAL PUBLIC LICENSE, Version 3", "GPL-3.0-only", "GPL-3.0-or-later", "EPL-2.0", "MIT", "MPL-2.0", "Public Domain")

            // Full license text for license IDs mentioned here will be included, even if no detected dependency uses them.
             additionalLicenses.addAll("apache_2_0", "gpl_2_1") // ffMpeg in ffMetadataEx
        }

        library {
            duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
            duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
        }
    }

    // for RB
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }

    // Room's JVM migration helper reads schemas from the target app assets.
    // Keep them in debug/test builds only; release APKs do not need the JSON files.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    lint {
        lintConfig = file("lint.xml")
    }

    androidResources {
        generateLocaleConfig = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.resValues.put(
            variant.makeResValueKey("string", "shortcut_target_package"),
            variant.applicationId.map { ResValue(it, "Package of this build variant for static shortcuts") },
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    // compose
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)
    implementation(libs.compose.icons.extended)

    // ui
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.shimmer)

    // material
    implementation(libs.adaptive)
    implementation(libs.material3)
    implementation(libs.palette)
    implementation(libs.material.color.utilities)

    // viewmodel
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.media3)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.workmanager)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)

    // modules
    implementation(project(":innertube"))
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":betterlyrics"))
    implementation(project(":simpmusic"))

    // misc
    implementation(libs.aboutlibraries.compose.m3)

    // local metadata tag extraction (TagLib, all flavors)
    implementation(libs.taglib)

    // sdk24 support
    // Support for N is officially unsupported even it the app should still work. Leave this outside of the version catalog.
    implementation("androidx.webkit:webkit:1.14.0")

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
}

afterEvaluate {
    dependencies {
        add("fullImplementation", files("../prebuilt/ffMetadataEx-release.aar"))
    }
}
