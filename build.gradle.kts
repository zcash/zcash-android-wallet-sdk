buildscript {
    repositories {
        google()
        gradlePluginPortal()
    }
    dependencies {
        classpath(kotlin("gradle-plugin", version = libs.versions.kotlin.get()))
        classpath(libs.gradle.plugin.rust)
        classpath(libs.gradle.plugin.navigation)
    }
}

plugins {
    id("com.github.ben-manes.versions")
    id("com.osacky.fulladle")
    id("zcash-sdk.detekt-conventions")
    id("zcash-sdk.ktlint-conventions")
    id("zcash-sdk.rosetta-conventions")
}

tasks {
    withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
        gradleReleaseChannel = "current"

        resolutionStrategy {
            componentSelection {
                all {
                    if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
                        reject("Unstable")
                    }
                }
            }
        }
    }

    register("checkProperties") {
        doLast {
            // Ensure that developers do not change default values of certain properties directly in the repo, but
            // instead set them in their local ~/.gradle/gradle.properties file (or use command line arguments)
            val expectedPropertyValues = mapOf(
                "mavenCentralUsername" to "",
                "mavenCentralPassword" to "",
                "ZCASH_ASCII_GPG_KEY" to "",

                "IS_SNAPSHOT" to "true",

                "ZCASH_IS_TREAT_WARNINGS_AS_ERRORS" to "true",

                "IS_USE_TEST_ORCHESTRATOR" to "false",

                "ZCASH_FIREBASE_TEST_LAB_API_KEY_PATH" to "",
                "ZCASH_FIREBASE_TEST_LAB_PROJECT" to "",

                "ZCASH_EMULATOR_WTF_API_KEY" to "",

                "IS_MINIFY_SDK_ENABLED" to "false",
                "IS_MINIFY_APP_ENABLED" to "true",

                "ZCASH_DEBUG_KEYSTORE_PATH" to "",
                "ZCASH_RELEASE_KEYSTORE_PATH" to "",
                "ZCASH_RELEASE_KEYSTORE_PASSWORD" to "",
                "ZCASH_RELEASE_KEY_ALIAS" to "",
                "ZCASH_RELEASE_KEY_ALIAS_PASSWORD" to "",

                "IS_SIGN_RELEASE_BUILD_WITH_DEBUG_KEY" to "false",
                
                "IS_DEBUGGABLE_WHILE_BENCHMARKING" to "false"
            )

            val warnings = expectedPropertyValues.filter { (key, value) ->
                project.properties[key].toString() != value
            }.map { "Property ${it.key} does not have expected value \"${it.value}\"" }

            if (warnings.isNotEmpty()) {
                throw GradleException(warnings.joinToString(separator = "\n"))
            }
        }
    }
}

val unstableKeywords = listOf("alpha", "beta", "rc", "m", "ea", "build")

fun isNonStable(version: String): Boolean {
    val versionLowerCase = version.lowercase()

    return unstableKeywords.any { versionLowerCase.contains(it) }
}

fladle {
    // Firebase Test Lab has min and max values that might differ from our project's
    // These are determined by `gcloud firebase test android models list`
    @Suppress("MagicNumber", "VariableNaming")
    val FIREBASE_TEST_LAB_MIN_API = 27 // Minimum for Pixel2.arm device

    @Suppress("MagicNumber", "VariableNaming")
    val FIREBASE_TEST_LAB_MAX_API = 34

    val minSdkVersion = run {
        val buildMinSdk = project.properties["ANDROID_MIN_SDK_VERSION"].toString().toInt()
        buildMinSdk.coerceAtLeast(FIREBASE_TEST_LAB_MIN_API).toString()
    }
    val targetSdkVersion = run {
        val buildTargetSdk = project.properties["ANDROID_TARGET_SDK_VERSION"].toString().toInt()
        buildTargetSdk.coerceAtMost(FIREBASE_TEST_LAB_MAX_API).toString()
    }

    val firebaseTestLabKeyPath = project.properties["ZCASH_FIREBASE_TEST_LAB_API_KEY_PATH"].toString()
    val firebaseProject = project.properties["ZCASH_FIREBASE_TEST_LAB_PROJECT"].toString()

    if (firebaseTestLabKeyPath.isNotEmpty()) {
        serviceAccountCredentials.set(File(firebaseTestLabKeyPath))
    } else if (firebaseProject.isNotEmpty()) {
        projectId.set(firebaseProject)
    }

    devices.addAll(
        mapOf("model" to "Pixel2.arm", "version" to minSdkVersion),
        mapOf("model" to "Pixel2.arm", "version" to targetSdkVersion)
    )

    @Suppress("MagicNumber")
    flakyTestAttempts.set(2)

    flankVersion.set(libs.versions.flank.get())

    filesToDownload.set(listOf(
        "*/matrix_*/*test_results_merged\\.xml",
        "*/matrix_*/*/artifacts/sdcard/googletest/test_outputfiles/*\\.png"
    ))

    directoriesToPull.set(listOf(
        "/sdcard/googletest/test_outputfiles"
    ))
}
