import com.android.build.gradle.LibraryExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.MavenPublishPlugin
import com.vanniktech.maven.publish.SonatypeHost
import java.util.Base64

val publicationVariant = "release"
val isSnapshot = project.property("IS_SNAPSHOT").toString().toBoolean()
val myVersion = project.property("LIBRARY_VERSION").toString()

val myGroup = "cash.z.ecc.android"
project.group = myGroup

plugins.apply(MavenPublishPlugin::class.java)
extensions.getByType<MavenPublishBaseExtension>().apply {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true
        )
    )
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    coordinates(
        groupId = myGroup,
        version = if (isSnapshot) "$myVersion-SNAPSHOT" else myVersion,
    )

    pom {
        name.set("Zcash Android Wallet SDK")
        description.set(
            "This lightweight SDK connects Android to Zcash, allowing third-party " +
                "Android apps to send and receive shielded transactions easily, securely and privately."
        )
        url.set("https://github.com/zcash/zcash-android-wallet-sdk/")
        inceptionYear.set("2018")
        scm {
            url.set("https://github.com/zcash/zcash-android-wallet-sdk/")
            connection.set("scm:git:git://github.com/zcash/zcash-android-wallet-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/zcash/zcash-android-wallet-sdk.git")
        }
        developers {
            developer {
                id.set("zcash")
                name.set("Zcash Open Development Lab")
                url.set("https://github.com/zcash/")
            }
        }
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/zcash/zcash-android-wallet-sdk/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
    }

    // signAllPublications()
}

plugins.apply("org.gradle.signing")
plugins.withId("org.gradle.signing") {
    project.the<SigningExtension>().apply {
        // Maven Central allows signing for both snapshot and release SDK versions
        isRequired = true

        val signingKey = run {
            val base64EncodedKey = project.property("ZCASH_ASCII_GPG_KEY").toString()
            if (base64EncodedKey.isNotEmpty()) {
                val keyBytes = Base64.getDecoder().decode(base64EncodedKey)
                String(keyBytes)
            } else {
                ""
            }
        }

        if (signingKey.isNotEmpty()) {
            useInMemoryPgpKeys(signingKey, "")
        }

        project.mavenPublications(
            object: Action<MavenPublication>{
                override fun execute(publication: MavenPublication) {
                    project.gradleSigning.sign(publication)
                }
            }
        )
    }
}

private fun Project.mavenPublications(action: Action<MavenPublication>) {
    gradlePublishing.publications.withType(MavenPublication::class.java).configureEach(action)
}

private inline val Project.gradlePublishing: PublishingExtension
    get() = extensions.getByType(PublishingExtension::class.java)

private inline val Project.gradleSigning: SigningExtension
    get() = extensions.getByType(SigningExtension::class.java)
