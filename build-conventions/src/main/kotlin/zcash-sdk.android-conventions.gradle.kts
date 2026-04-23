import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ManagedVirtualDevice
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

pluginManager.withPlugin("com.android.application") {
    project.the<com.android.build.gradle.AppExtension>().apply {
        configureBaseExtension()

        defaultConfig {
            minSdk = project.property("ANDROID_MIN_SDK_VERSION").toString().toInt()
            targetSdk = project.property("ANDROID_TARGET_SDK_VERSION").toString().toInt()

            multiDexEnabled = true

            // en_XA and ar_XB are pseudolocales for debugging.
            // The rest of the locales provides an explicit list of the languages to keep in the
            // final app.  Doing this will strip out additional locales from libraries like
            // Google Play Services and Firebase, which add unnecessary bloat.
            resourceConfigurations.addAll(listOf("en", "en-rUS", "en-rGB", "en-rAU", "en_XA", "ar_XB"))

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
                testInstrumentationRunnerArguments["clearPackageData"] = "true"
            }
        }
    }
}

pluginManager.withPlugin("com.android.library") {
    project.the<com.android.build.gradle.LibraryExtension>().apply {
        configureBaseExtension()

        defaultConfig {
            minSdk = project.property("ANDROID_MIN_SDK_VERSION").toString().toInt()
            // This is deprecated but there isn't a replacement for it yet with instrumentation tests.
            lint.targetSdk = project.property("ANDROID_TARGET_SDK_VERSION").toString().toInt()
            testOptions.targetSdk = project.property("ANDROID_TARGET_SDK_VERSION").toString().toInt()

            multiDexEnabled = true

            // The last two are for support of pseudolocales in debug builds.
            // If we add other localizations, they should be included in this list.
            // By explicitly setting supported locales, we strip out unused localizations from third party
            // libraries (e.g. play services)
            resourceConfigurations.addAll(listOf("en", "en-rUS", "en-rGB", "en-rAU", "en_XA", "ar_XB"))

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            consumerProguardFiles("proguard-consumer.txt")

            if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
                testInstrumentationRunnerArguments["clearPackageData"] = "true"
            }
        }
        testCoverage {
            jacocoVersion = project.property("JACOCO_VERSION").toString()
        }
    }

    project.the<com.android.build.api.variant.LibraryAndroidComponentsExtension>().apply {
        onVariants { variant ->
            if (variant.name.lowercase(Locale.US).contains("release")) {
                variant.packaging.resources.excludes.addAll(
                    listOf(
                        "META-INF/ASL2.0",
                        "META-INF/DEPENDENCIES",
                        "META-INF/LICENSE",
                        "META-INF/LICENSE-notice.md",
                        "META-INF/LICENSE.md",
                        "META-INF/LICENSE.txt",
                        "META-INF/NOTICE",
                        "META-INF/NOTICE.txt",
                        "META-INF/license.txt",
                        "META-INF/notice.txt"
                    )
                )
            }
        }
    }
}

pluginManager.withPlugin("com.android.test") {
    project.the<com.android.build.gradle.TestExtension>().apply {
        configureBaseExtension()

        defaultConfig {
            minSdk = project.property("ANDROID_MIN_SDK_VERSION").toString().toInt()
            targetSdk = project.property("ANDROID_TARGET_SDK_VERSION").toString().toInt()

            multiDexEnabled = true

            // The last two are for support of pseudolocales in debug builds.
            // If we add other localizations, they should be included in this list.
            // By explicitly setting supported locales, we strip out unused localizations from third party
            // libraries (e.g. play services)
            resourceConfigurations.addAll(listOf("en", "en-rUS", "en-rGB", "en-rAU", "en_XA", "ar_XB"))

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
                testInstrumentationRunnerArguments["clearPackageData"] = "true"
            }
        }
        testCoverage {
            jacocoVersion = project.property("JACOCO_VERSION").toString()
        }
    }
}

@Suppress("LongMethod")
fun com.android.build.gradle.BaseExtension.configureBaseExtension() {
    compileSdkVersion(project.property("ANDROID_COMPILE_SDK_VERSION").toString().toInt())
    ndkVersion = project.property("ANDROID_NDK_VERSION").toString()

    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        val javaVersion = JavaVersion.toVersion(project.property("ANDROID_JVM_TARGET").toString())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    buildTypes {
        getByName("debug").apply {
            isTestCoverageEnabled = project.property("IS_ANDROID_INSTRUMENTATION_TEST_COVERAGE_ENABLED")
                .toString().toBoolean()
        }
    }

    signingConfigs {
        val debugKeystorePath = project.property("ZCASH_DEBUG_KEYSTORE_PATH").toString()
        val isExplicitDebugSigningEnabled = !debugKeystorePath.isNullOrBlank()
        if (isExplicitDebugSigningEnabled) {
            // If this block doesn't execute, the output will still be signed with the default keystore
            getByName("debug").apply {
                storeFile = File(debugKeystorePath)
            }
        }
    }

    testOptions {
        animationsDisabled = true

        // Make Android framework calls (e.g. android.util.Log.*) return default no-op values in
        // local unit tests instead of throwing "Method ... not mocked". This is needed by code
        // under test that invokes android.util.Log via Twig (e.g. ScanRange.new).
        unitTests.isReturnDefaultValues = true

        if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
            execution = "ANDROIDX_TEST_ORCHESTRATOR"
        }

        @Suppress("UnstableApiUsage")
        managedDevices {
            @Suppress("MagicNumber", "VariableNaming")
            val MANAGED_DEVICES_MIN_SDK = 27

            val testDeviceMinSdkVersion = project.properties["ANDROID_MIN_SDK_VERSION"]
                .toString().toInt().coerceAtLeast(MANAGED_DEVICES_MIN_SDK)
            val testDeviceMaxSdkVersion = project.properties["ANDROID_TARGET_SDK_VERSION"].toString().toInt()

            allDevices {
                create<ManagedVirtualDevice>("pixel2Min") {
                    device = "Pixel 2"
                    apiLevel = testDeviceMinSdkVersion
                    systemImageSource = "aosp"
                }
                create<ManagedVirtualDevice>("pixel2Target") {
                    device = "Pixel 2"
                    apiLevel = testDeviceMaxSdkVersion
                    systemImageSource = "aosp"
                }
            }
        }
    }

    dependencies {
        add(
            "coreLibraryDesugaring",
            "com.android.tools:desugar_jdk_libs:${project.property("CORE_LIBRARY_DESUGARING_VERSION")}"
        )
    }

    if (this is CommonExtension<*, *, *, *, *, *>) {
        project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(project.property("ANDROID_JVM_TARGET").toString()))
                allWarningsAsErrors.set(
                    project.property("ZCASH_IS_TREAT_WARNINGS_AS_ERRORS").toString().toBoolean()
                )
                freeCompilerArgs.addAll(
                    "-opt-in=kotlin.RequiresOptIn"
                )
            }
        }
    }
}
