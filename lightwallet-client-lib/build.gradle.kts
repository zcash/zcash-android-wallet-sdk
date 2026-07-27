import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("zcash-sdk.android-conventions")

    id("org.jetbrains.dokka")
    id("com.google.protobuf")

    id("wtf.emulator.gradle")
    id("zcash-sdk.emulator-wtf-conventions")

    id("maven-publish")
    id("signing")
    id("zcash-sdk.publishing-conventions")
}

mavenPublishing {
    coordinates(artifactId = "lightwallet-client")
}

android {
    namespace = "co.electriccoin.lightwallet.client"
    useLibrary("android.test.runner")

    defaultConfig {
        consumerProguardFiles("proguard-consumer.txt")
    }

    buildFeatures {
        buildConfig = true
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

    sourceSets.getByName("main") {
        proto { srcDir("src/main/proto") }
    }

    lint {
        baseline = File("lint-baseline.xml")
    }
}

// lightwallet-protocol v0.5.0 marks GetBlockNullifiers and GetBlockRangeNullifiers
// deprecated in favour of GetBlockRange with `poolTypes`. The grpckt generator emits
// stubs for them and calls them from its own generated code, so the warnings arise
// entirely within build/generated/ and cannot be annotated away at the source. The
// module-wide `allWarningsAsErrors` would otherwise fail the build on them.
//
// Scoped to DEPRECATION rather than disabling warnings-as-errors for the module, so
// every other warning class is still an error here. Remove this once the deprecated
// RPCs are dropped upstream and the generated stubs go with them.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xwarning-level=DEPRECATION:disabled")
    }
}

tasks.dokkaHtml.configure {
    dokkaSourceSets {
        configureEach {
            outputDirectory.set(file("build/docs/rtd"))
            displayName.set("Lightwallet Client")
            includes.from("packages.md")
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protoc.compiler.get().asCoordinateString()
    }
    plugins {
        id("java") {
            artifact = libs.protoc.gen.java.get().asCoordinateString()
        }
        id("grpc") {
            artifact = libs.protoc.gen.java.get().asCoordinateString()
        }
        id("grpckt") {
            artifact = libs.protoc.gen.kotlin.get().asCoordinateString() + ":jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("java") {
                    option("lite")
                }
                id("grpc") {
                    option("lite")
                }
                id("grpckt") {
                    option("lite")
                }
            }
            it.builtins {
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.bundles.grpc)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Tests
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.grpc.testing)

    androidTestImplementation(libs.androidx.multidex)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
}

tasks {
    getByName("preBuild").dependsOn(register("bugfixTask") {
        doFirst {
            mkdir("build/extracted-include-protos/main")
        }
    })
}

fun MinimalExternalModuleDependency.asCoordinateString() =
    "${module.group}:${module.name}:${versionConstraint.displayName}"
