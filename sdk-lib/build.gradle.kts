import com.android.build.api.variant.BuildConfigField

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("zcash-sdk.android-conventions")

    id("org.jetbrains.kotlin.plugin.allopen")
    id("org.jetbrains.dokka")

    id("wtf.emulator.gradle")
    id("zcash-sdk.emulator-wtf-conventions")

    id("maven-publish")
    id("signing")
    id("zcash-sdk.publishing-conventions")
}

// Publishing information

val myVersion = project.property("LIBRARY_VERSION").toString()
val myArtifactId = "zcash-android-sdk"

mavenPublishing {
    coordinates(
        artifactId = myArtifactId
    )
}

android {
    namespace = "cash.z.ecc.android.sdk"

    useLibrary("android.test.runner")

    defaultConfig {
        consumerProguardFiles("proguard-consumer.txt")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug").apply {
            // test builds exceed the dex limit because they pull in large test libraries
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

    // Tricky: fix: By default, the kotlin_module name will not include the version (in classes.jar/META-INF).
    // Instead it has a colon, which breaks compilation on Windows. This is one way to set it explicitly to the
    // proper value. See https://github.com/zcash/zcash-android-wallet-sdk/issues/222 for more info.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll("-module-name", "$myArtifactId-${myVersion}_release")
        }
    }

    lint {
        baseline = File("lint-baseline.xml")
    }
}

androidComponents {
    onVariants { variant ->
        // Add library version to be available for runtime
        variant.buildConfigFields?.put(
            "LIBRARY_VERSION",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("LIBRARY_VERSION").toString()}\"",
                comment = "Current library version"
            )
        )
    }
}

allOpen {
    // marker for classes that we want to be able to extend in debug builds for testing purposes
    annotation("cash.z.ecc.android.sdk.annotation.OpenClass")
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
    api(libs.kotlinx.datetime)
    api(projects.lightwalletClientLib)
    implementation(projects.backendLib)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.security.crypto)

    // Architecture Components: Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)

    // For direct database access
    // TODO [#703]: Eliminate this dependency
    // https://github.com/zcash/zcash-android-wallet-sdk/issues/703
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.framework)

    // Metrics
    implementation(libs.androidx.tracing)

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Tests
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.bundles.junit)

    // NOTE: androidTests will use JUnit4, while src/test/java tests will leverage Junit5
    // Attempting to use JUnit5 via https://github.com/mannodermaus/android-junit5 was painful. The plugin configuration
    // was buggy, crashing in several places. It also would require a separate test flavor because it's minimum API 26
    // because "JUnit 5 uses Java 8-specific APIs that didn't exist on Android before the Oreo release."
    androidTestImplementation(libs.androidx.multidex)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // sample mnemonic plugin
    androidTestImplementation(libs.zcashwalletplgn)
    androidTestImplementation(libs.bip39)
}

fun MinimalExternalModuleDependency.asCoordinateString() =
    "${module.group}:${module.name}:${versionConstraint.displayName}"
