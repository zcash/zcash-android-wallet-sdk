# Overview
We aim for the main branch of the repository to always be in a releasable state.

Two types of artifacts can be published:
1. Snapshot — An unstable release of the SDK for testing
1. Release — A stable release of the SDK

Control of these modes of release is managed with a Gradle property `IS_SNAPSHOT`.

For both snapshot and release publishing, there are two ways to initiate deployment:
1. Automatically
2. Manually

This document will focus initially on the automated process, with a section at the end on manual process.  (The automated process more or less implements the manual process via GitHub Actions.)

# Automated Publishing
## Snapshots
Every push to a branch matching `release/**` triggers an automated [snapshot deployment](https://github.com/zcash/zcash-android-wallet-sdk/actions/workflows/deploy-snapshot.yml).  The workflow can also be dispatched manually against any branch.  Pushes that only touch `docs/**`, `README.md`, `LICENSE`, or the issue/PR templates are ignored.

Note that snapshots do not have a stable API, so clients should not depend on a snapshot.  The primary reason this is documented is for testing, e.g. before deploying a new production version of the SDK we may test against the snapshot first.

Snapshots can be consumed by:

1. Adding the snapshot repository
settings.gradle.kts:
```
dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            // Optional; ensures only explicitly declared dependencies come from this repository
            content {
                includeGroup("cash.z.ecc.android")
            }
        }
    }
}
```

2. Changing the dependency version to end with `-SNAPSHOT`

3. Rebuilding
`./gradlew assemble --refresh-dependencies`

Because Gradle caches dependencies and because multiple snapshots can be deployed under the same version number, using `--refresh-dependencies` is important to ensure the latest snapshot is pulled.  (#533 will make it easier to identify version of the snapshot in the future).

## Releases
Production releases can be consumed using the instructions in the [README.MD](../README.md).  Note that production releases can include alpha or beta designations.

Automated production releases require a manual trigger of the GitHub action and a manual step inside the Sonatype Central Portal.  The release workflow only uploads a deployment; it does not publish it.  To do a production release:
1. Update the [CHANGELOG](../CHANGELOG.md) for any new changes since the last production release.
1. Run the [release deployment](https://github.com/zcash/zcash-android-wallet-sdk/actions/workflows/deploy-release.yml).  This is a `workflow_dispatch` workflow, so choose the branch to release from; it publishes whatever `LIBRARY_VERSION` that branch carries in [gradle.properties](../gradle.properties).
1. Log into the [Sonatype Central Portal](https://central.sonatype.com/) and publish the deployment.
    1. Find the new deployment and wait for it to reach the validated state
    1. Check its contents, to verify it looks correct
    1. Publish the deployment
1. Confirm deployment succeeded by modifying the [Zashi Wallet](https://github.com/Electric-Coin-Company/zashi-android) to consume the new SDK version.
1. Create a new Git tag for the new release in this repository, following the existing `vMAJOR.MINOR.PATCH` convention.  Tags are documentary only; no workflow is triggered by them.
1. Create a new pull request bumping the version to the next version (this ensures that the next push to a release branch creates a snapshot under the next version number).

# Manual Publishing
See [CI.md](CI.md), which describes the continuous integration workflow for deployment and describes the secrets that 
would need to be configured in a repository fork.

## One time only
* Set up environment to [compile the SDK](https://github.com/zcash/zcash-android-wallet-sdk/#compiling-sources)
* Create file `~/.gradle/gradle.properties`
  * add your sonotype credentials with these properties
      * `mavenCentralUsername`
      * `mavenCentralPassword`
  * Point it to a passwordless GPG key that has been ASCII armored, then base64 encoded.
     * `ZCASH_ASCII_GPG_KEY`

## Every time
1. Update the [build number](https://github.com/zcash/zcash-android-wallet-sdk/blob/main/gradle.properties) and the [CHANGELOG](../CHANGELOG.md).  For release builds, suffix the Gradle invocations below with `-PIS_SNAPSHOT=false`.
1. Build locally
    * This will install the files in your local maven repo at `~/.m2/repository/cash/z/ecc/android/`
```zsh
./gradlew publishToMavenLocal
```
1. Publish via the following command:
    1. Snapshot: `./gradlew publishToMavenCentral -PIS_SNAPSHOT=true`
    2. Release
        1. `./gradlew publishToMavenCentral -PIS_SNAPSHOT=false`
        2. Log into the [Sonatype Central Portal](https://central.sonatype.com/) to publish the uploaded deployment.
        3. Alternatively, `./gradlew publishAndReleaseToMavenCentral -PIS_SNAPSHOT=false` uploads and publishes in one step, skipping the manual review.

### Artifacts availability 
- Our existing release artifacts can be found here and here:
   - https://search.maven.org/artifact/cash.z.ecc.android/zcash-android-sdk
   - https://repo1.maven.org/maven2/cash/z/ecc/android/

- And our snapshot artifacts here:
   - https://central.sonatype.com/repository/maven-snapshots/cash/z/ecc/android/

### Obtain new user token
1. Log in to the Sonatype Central Portal:
   - Go to https://central.sonatype.com/ and log in with your Sonatype credentials
1. Access the user token page:
   - Click on your username in the top right corner
   - Select **View Account**
1. Generate a new token:
   - Click the **Generate User Token** button
   - The generated username/password pair maps to the `mavenCentralUsername` and `mavenCentralPassword` Gradle properties

Note: Generating a new user token invalidates the previous one, so the `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` repository secrets need to be updated at the same time.
