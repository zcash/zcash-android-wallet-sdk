enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        val isRepoRestrictionEnabled = true
        mavenCentral {
            if (isRepoRestrictionEnabled) {
                content {
                    includeGroup("wtf.emulator")
                }
            }
        }
        gradlePluginPortal()
        google()
    }

    plugins {
        val androidGradlePluginVersion = extra["ANDROID_GRADLE_PLUGIN_VERSION"].toString()
        val detektVersion = extra["DETEKT_VERSION"].toString()
        val dokkaVersion = extra["DOKKA_VERSION"].toString()
        val emulatorWtfGradlePluginVersion = extra["EMULATOR_WTF_GRADLE_PLUGIN_VERSION"].toString()
        val fulladleVersion = extra["FULLADLE_VERSION"].toString()
        val gradleVersionsPluginVersion = extra["GRADLE_VERSIONS_PLUGIN_VERSION"].toString()
        val kotlinVersion = extra["KOTLIN_VERSION"].toString()
        val kspVersion = extra["KSP_VERSION"].toString()
        val protobufVersion = extra["PROTOBUF_GRADLE_PLUGIN_VERSION"].toString()
        val toolchainResolverVersion = extra["FOOJAY_TOOLCHAIN_RESOLVER_VERSION"].toString()

        id("com.android.application") version (androidGradlePluginVersion) apply (false)
        id("com.android.library") version (androidGradlePluginVersion) apply (false)
        id("com.android.test") version (androidGradlePluginVersion) apply (false)
        id("com.github.ben-manes.versions") version (gradleVersionsPluginVersion) apply (false)
        id("com.google.devtools.ksp") version(kspVersion) apply (false)
        id("com.google.protobuf") version (protobufVersion) apply (false)
        id("com.osacky.fulladle") version (fulladleVersion) apply (false)
        id("io.gitlab.arturbosch.detekt") version (detektVersion) apply (false)
        id("org.gradle.toolchains.foojay-resolver-convention") version(toolchainResolverVersion) apply (false)
        id("org.jetbrains.dokka") version (dokkaVersion) apply (false)
        id("org.jetbrains.kotlin.android") version (kotlinVersion) apply (false)
        id("org.jetbrains.kotlin.plugin.allopen") version (kotlinVersion) apply (false)
        id("org.jetbrains.kotlin.plugin.compose") version (kotlinVersion) apply (false)
        id("wtf.emulator.gradle") version (emulatorWtfGradlePluginVersion) apply (false)
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        val isRepoRestrictionEnabled = true

        google()
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots") {
            mavenContent {
                snapshotsOnly()
            }
            if (isRepoRestrictionEnabled) {
                content {
                    includeGroup("cash.z.ecc.android")
                }
            }
        }
        maven("https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
        maven("https://maven.emulator.wtf/releases/") {
            if (isRepoRestrictionEnabled) {
                content {
                    includeGroup("wtf.emulator")
                }
            }
        }
    }

    @Suppress("MaxLineLength")
    versionCatalogs {
        create("libs") {
            val androidGradlePluginVersion = extra["ANDROID_GRADLE_PLUGIN_VERSION"].toString()
            val androidxActivityVersion = extra["ANDROIDX_ACTIVITY_VERSION"].toString()
            val androidxAnnotationVersion = extra["ANDROIDX_ANNOTATION_VERSION"].toString()
            val androidxComposeCompilerVersion = extra["ANDROIDX_COMPOSE_COMPILER_VERSION"].toString()
            val androidxComposeMaterial3Version = extra["ANDROIDX_COMPOSE_MATERIAL3_VERSION"].toString()
            val androidxComposeMaterialIconsVersion = extra["ANDROIDX_COMPOSE_MATERIAL_ICONS_VERSION"].toString()
            val androidxComposeVersion = extra["ANDROIDX_COMPOSE_VERSION"].toString()
            val androidxConstraintLayoutVersion = extra["ANDROIDX_CONSTRAINT_LAYOUT_VERSION"].toString()
            val androidxCoreVersion = extra["ANDROIDX_CORE_VERSION"].toString()
            val androidxDatabaseVersion = extra["ANDROIDX_DATABASE_VERSION"].toString()
            val androidxEspressoVersion = extra["ANDROIDX_ESPRESSO_VERSION"].toString()
            val androidxLifecycleVersion = extra["ANDROIDX_LIFECYCLE_VERSION"].toString()
            val androidxMultidexVersion = extra["ANDROIDX_MULTIDEX_VERSION"].toString()
            val androidxNavigationVersion = extra["ANDROIDX_NAVIGATION_VERSION"].toString()
            val androidxNavigationComposeVersion = extra["ANDROIDX_NAVIGATION_COMPOSE_VERSION"].toString()
            val androidxNavigationFragmentVersion = extra["ANDROIDX_NAVIGATION_FRAGMENT_VERSION"].toString()
            val androidxProfileInstallerVersion = extra["ANDROIDX_PROFILE_INSTALLER_VERSION"].toString()
            val androidxSecurityCryptoVersion = extra["ANDROIDX_SECURITY_CRYPTO_VERSION"].toString()
            val androidxTestJunitVersion = extra["ANDROIDX_TEST_JUNIT_VERSION"].toString()
            val androidxTestMacrobenchmarkVersion = extra["ANDROIDX_TEST_MACROBENCHMARK_VERSION"].toString()
            val androidxTestOrchestratorVersion = extra["ANDROIDX_TEST_ORCHESTRATOR_VERSION"].toString()
            val androidxTestCoreVersion = extra["ANDROIDX_TEST_CORE_VERSION"].toString()
            val androidxTestRunnerVersion = extra["ANDROIDX_TEST_RUNNER_VERSION"].toString()
            val androidxTracingVersion = extra["ANDROIDX_TRACING_VERSION"].toString()
            val androidxUiAutomatorVersion = extra["ANDROIDX_UI_AUTOMATOR_VERSION"].toString()
            val bip39Version = extra["BIP39_VERSION"].toString()
            val flankVersion = extra["FLANK_VERSION"].toString()
            val googleMaterialVersion = extra["GOOGLE_MATERIAL_VERSION"].toString()
            val grpcJavaVersion = extra["GRPC_VERSION"].toString()
            val grpcKotlinVersion = extra["GRPC_KOTLIN_VERSION"].toString()
            val gsonVersion = extra["GSON_VERSION"].toString()
            val javaVersion = extra["ANDROID_JVM_TARGET"].toString()
            val javaxAnnotationVersion = extra["JAVAX_ANNOTATION_VERSION"].toString()
            val junitVersion = extra["JUNIT_VERSION"].toString()
            val kotlinVersion = extra["KOTLIN_VERSION"].toString()
            val kotlinxCoroutinesVersion = extra["KOTLINX_COROUTINES_VERSION"].toString()
            val kotlinxDateTimeVersion = extra["KOTLINX_DATETIME_VERSION"].toString()
            val kotlinxImmutableCollectionsVersion = extra["KOTLINX_IMMUTABLE_COLLECTIONS_VERSION"].toString()
            val ktorVersion = extra["KTOR_VERSION"].toString()
            val mockitoVersion = extra["MOCKITO_VERSION"].toString()
            val protocVersion = extra["PROTOC_VERSION"].toString()
            val rustGradlePluginVersion = extra["RUST_GRADLE_PLUGIN_VERSION"].toString()
            val zcashWalletPluginVersion = extra["ZCASH_WALLET_PLUGINS_VERSION"].toString()

            // Standalone versions
            version("flank", flankVersion)
            version("grpc", grpcJavaVersion)
            version("java", javaVersion)
            version("kotlin", kotlinVersion)
            version("protoc", protocVersion)

            // Aliases
            // Gradle plugins
            library("gradle-plugin-android", "com.android.tools.build:gradle:$androidGradlePluginVersion")
            library("gradle-plugin-navigation", "androidx.navigation:navigation-safe-args-gradle-plugin:$androidxNavigationVersion")
            library("gradle-plugin-rust", "org.mozilla.rust-android-gradle:plugin:$rustGradlePluginVersion")

            // Special cases used by the grpc gradle plugin
            library("protoc-compiler", "com.google.protobuf:protoc:$protocVersion")
            library("protoc-gen-java", "io.grpc:protoc-gen-grpc-java:$grpcJavaVersion")
            library("protoc-gen-kotlin", "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion")

            // Libraries
            library("androidx-annotation", "androidx.annotation:annotation:$androidxAnnotationVersion")
            library("androidx-activity-compose", "androidx.activity:activity-compose:$androidxActivityVersion")
            library("androidx-constraintlayout", "androidx.constraintlayout:constraintlayout:$androidxConstraintLayoutVersion")
            library("androidx-core", "androidx.core:core-ktx:$androidxCoreVersion")
            library("androidx-lifecycle-common", "androidx.lifecycle:lifecycle-common-java8:$androidxLifecycleVersion")
            library("androidx-lifecycle-compose", "androidx.lifecycle:lifecycle-runtime-compose:$androidxLifecycleVersion")
            library("androidx-lifecycle-runtime", "androidx.lifecycle:lifecycle-runtime-ktx:$androidxLifecycleVersion")
            library("androidx-multidex", "androidx.multidex:multidex:$androidxMultidexVersion")
            library("androidx-navigation-compose", "androidx.navigation:navigation-compose:$androidxNavigationComposeVersion")
            library("androidx-navigation-fragment", "androidx.navigation:navigation-fragment-ktx:$androidxNavigationFragmentVersion")
            library("androidx-navigation-ui", "androidx.navigation:navigation-ui-ktx:$androidxNavigationVersion")
            library("androidx-profileinstaller", "androidx.profileinstaller:profileinstaller:$androidxProfileInstallerVersion")
            library("androidx-sqlite", "androidx.sqlite:sqlite-ktx:${androidxDatabaseVersion}")
            library("androidx-sqlite-framework", "androidx.sqlite:sqlite-framework:${androidxDatabaseVersion}")
            library("androidx-viewmodel-compose", "androidx.lifecycle:lifecycle-viewmodel-compose:$androidxLifecycleVersion")
            library("bip39", "cash.z.ecc.android:kotlin-bip39:$bip39Version")
            library("grpc-android", "io.grpc:grpc-android:$grpcJavaVersion")
            library("grpc-kotlin", "com.google.protobuf:protobuf-kotlin-lite:$protocVersion")
            library("grpc-kotlin-stub", "io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
            library("grpc-okhttp", "io.grpc:grpc-okhttp:$grpcJavaVersion")
            library("grpc-protobuf", "io.grpc:grpc-protobuf-lite:$grpcJavaVersion")
            library("grpc-stub", "io.grpc:grpc-stub:$grpcJavaVersion")
            library("gson", "com.google.code.gson:gson:$gsonVersion")
            library("javax-annotation", "javax.annotation:javax.annotation-api:$javaxAnnotationVersion")
            library("kotlin-reflect", "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
            library("kotlin-stdlib", "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            library("kotlinx-coroutines-android", "org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")
            library("kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
            library("kotlinx-datetime", "org.jetbrains.kotlinx:kotlinx-datetime:$kotlinxDateTimeVersion")
            library("kotlinx-immutable", "org.jetbrains.kotlinx:kotlinx-collections-immutable:$kotlinxImmutableCollectionsVersion")
            library("ktor-core", "io.ktor:ktor-client-core:$ktorVersion")
            library("material", "com.google.android.material:material:$googleMaterialVersion")
            library("zcashwalletplgn", "com.github.zcash:zcash-android-wallet-plugins:$zcashWalletPluginVersion")

            // Demo app
            library("androidx-compose-foundation", "androidx.compose.foundation:foundation:$androidxComposeVersion")
            library("androidx-compose-material3", "androidx.compose.material3:material3:$androidxComposeMaterial3Version")
            library("androidx-compose-material-icons-core", "androidx.compose.material:material-icons-core:$androidxComposeMaterialIconsVersion")
            library("androidx-compose-material-icons-extended", "androidx.compose.material:material-icons-extended:$androidxComposeMaterialIconsVersion")
            library("androidx-compose-tooling", "androidx.compose.ui:ui-tooling:$androidxComposeVersion")
            library("androidx-compose-ui", "androidx.compose.ui:ui:$androidxComposeVersion")
            library("androidx-compose-ui-fonts", "androidx.compose.ui:ui-text-google-fonts:$androidxComposeVersion")
            library("androidx-compose-compiler", "androidx.compose.compiler:compiler:$androidxComposeCompilerVersion")
            library("androidx-security-crypto", "androidx.security:security-crypto-ktx:$androidxSecurityCryptoVersion")

            // Test libraries
            library("androidx-compose-test-junit", "androidx.compose.ui:ui-test-junit4:$androidxComposeVersion")
            library("androidx-compose-test-manifest", "androidx.compose.ui:ui-test-manifest:$androidxComposeVersion")
            library("androidx-espresso-contrib", "androidx.test.espresso:espresso-contrib:$androidxEspressoVersion")
            library("androidx-espresso-core", "androidx.test.espresso:espresso-core:$androidxEspressoVersion")
            library("androidx-espresso-intents", "androidx.test.espresso:espresso-intents:$androidxEspressoVersion")
            library("androidx-test-core", "androidx.test:core:$androidxTestCoreVersion")
            library("androidx-test-junit", "androidx.test.ext:junit:$androidxTestJunitVersion")
            library("androidx-test-macrobenchmark", "androidx.benchmark:benchmark-macro-junit4:$androidxTestMacrobenchmarkVersion")
            library("androidx-test-runner", "androidx.test:runner:$androidxTestRunnerVersion")
            library("androidx-test-orchestrator", "androidx.test:orchestrator:$androidxTestOrchestratorVersion")
            library("androidx-tracing", "androidx.tracing:tracing:$androidxTracingVersion")
            library("androidx-uiAutomator", "androidx.test.uiautomator:uiautomator:$androidxUiAutomatorVersion")
            library("grpc-testing", "io.grpc:grpc-testing:$grpcJavaVersion")
            library("junit-api", "org.junit.jupiter:junit-jupiter-api:$junitVersion")
            library("junit-engine", "org.junit.jupiter:junit-jupiter-engine:$junitVersion")
            library("junit-migration", "org.junit.jupiter:junit-jupiter-migrationsupport:$junitVersion")
            library("kotlin-test", "org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
            library("kotlinx-coroutines-test", "org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
            library("mockito-android", "org.mockito:mockito-android:$mockitoVersion")
            library("mockito-junit", "org.mockito:mockito-junit-jupiter:$mockitoVersion")

            // Bundles
            bundle(
                "grpc",
                listOf(
                    "grpc-android",
                    "grpc-kotlin",
                    "grpc-kotlin-stub",
                    "grpc-okhttp",
                    "grpc-protobuf",
                    "grpc-stub"
                )
            )

            bundle(
                "protobuf",
                listOf(
                    "grpc-kotlin",
                    "grpc-protobuf",
                )
            )

            bundle(
                "androidx-compose-core",
                listOf(
                    "androidx-compose-compiler",
                    "androidx-compose-foundation",
                    "androidx-compose-material3",
                    "androidx-compose-tooling",
                    "androidx-compose-ui",
                    "androidx-compose-ui-fonts"
                )
            )
            bundle(
                "androidx-compose-extended",
                listOf(
                    "androidx-activity-compose",
                    "androidx-compose-material-icons-core",
                    "androidx-compose-material-icons-extended",
                    "androidx-lifecycle-compose",
                    "androidx-navigation-compose",
                    "androidx-viewmodel-compose"
                )
            )

            bundle(
                "androidx-test",
                listOf(
                    "androidx-espresso-core",
                    "androidx-espresso-intents",
                    "androidx-test-junit",
                    "androidx-test-core"
                )
            )

            bundle(
                "junit",
                listOf(
                    "junit-api",
                    "junit-engine",
                    "junit-migration",
                )
            )
        }
    }
}

rootProject.name = "zcash-android-sdk"

includeBuild("build-conventions")

include("backend-lib")
include("darkside-test-lib")
include("demo-app")
include("demo-app-benchmark-test")
include("lightwallet-client-lib")
include("sdk-incubator-lib")
include("sdk-lib")
