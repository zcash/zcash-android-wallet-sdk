plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("zcash-sdk.android-conventions")

    id("org.jetbrains.dokka")

    id("wtf.emulator.gradle")
    id("zcash-sdk.emulator-wtf-conventions")

    id("maven-publish")
    id("signing")
    id("zcash-sdk.publishing-conventions")
}

mavenPublishing {
    coordinates(
        artifactId = "zcash-android-sdk-slipstream"
    )
}

android {
    namespace = "com.zodl.slipstream"

    useLibrary("android.test.runner")

    defaultConfig {
        consumerProguardFiles("proguard-consumer.txt")
    }

    buildTypes {
        getByName("debug").apply {
            isMinifyEnabled = false
        }
        getByName("release").apply {
            isMinifyEnabled = project.property("IS_MINIFY_SDK_ENABLED").toString().toBoolean()
            proguardFiles.addAll(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    File("proguard-project.txt")
                )
            )
        }
        create("benchmark") {
            // We provide the extra benchmark build type just for benchmarking purposes
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }
}

/**
 * slipstream-lib was extracted out of sdk-lib and still uses its `internal` declarations.
 * Registering sdk-lib as a Kotlin friend module preserves that access across the module
 * boundary at compile time without widening sdk-lib's public API for external consumers.
 * The compiler matches friend paths by directory prefix, so sdk-lib's build directory covers
 * the per-variant intermediates for every variant and compilation (main, unit test, androidTest).
 */
val sdkLibBuildDir = project(":sdk-lib").layout.buildDirectory
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    friendPaths.from(sdkLibBuildDir)
}

tasks.dokkaHtml.configure {
    dokkaSourceSets {
        configureEach {
            outputDirectory.set(file("build/docs/rtd"))
            displayName.set("Zcash Android SDK")
            includes.from("packages.md")
        }
    }
}

dependencies {
    // The Slipstream engine talks to the Rust JNI surface directly, and sdk-lib exposes backend-lib
    // as an `implementation` dependency only, so Backend/RustBackend/TorClient do not arrive
    // transitively and backend-lib has to be declared here as well.
    implementation(projects.sdkLib)
    implementation(projects.backendLib)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Tests
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.bundles.junit)
    testImplementation(libs.mockito.junit)

    androidTestImplementation(libs.androidx.multidex)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
