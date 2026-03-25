package cash.z.ecc.android.sdk.internal.model.ext

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.model.Checkpoint
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import org.json.JSONException
import org.json.JSONObject

// Version is not returned from the server, so version 1 is implied.  A version is declared here
// to structure the parsing to be version-aware in the future.
internal val Checkpoint.Companion.VERSION_1
    get() = 1
internal val Checkpoint.Companion.KEY_VERSION
    get() = "version"
internal val Checkpoint.Companion.KEY_HEIGHT
    get() = "height"
internal val Checkpoint.Companion.KEY_HASH
    get() = "hash"
internal val Checkpoint.Companion.KEY_EPOCH_SECONDS
    get() = "time"
internal val Checkpoint.Companion.KEY_SAPLING_TREE
    get() = "saplingTree"
internal val Checkpoint.Companion.KEY_ORCHARD_TREE
    get() = "orchardTree"

internal fun Checkpoint.Companion.from(
    zcashNetwork: ZcashNetwork,
    jsonString: String
) = from(zcashNetwork, JSONObject(jsonString))

private fun Checkpoint.Companion.from(
    zcashNetwork: ZcashNetwork,
    jsonObject: JSONObject
): Checkpoint {
    when (val version = jsonObject.optInt(Checkpoint.KEY_VERSION, Checkpoint.VERSION_1)) {
        Checkpoint.VERSION_1 -> {
            val height =
                run {
                    val heightLong = jsonObject.getLong(Checkpoint.KEY_HEIGHT)
                    BlockHeight.new(heightLong)
                }
            val hash = jsonObject.getString(Checkpoint.KEY_HASH)
            val epochSeconds = jsonObject.getLong(Checkpoint.KEY_EPOCH_SECONDS)
            val saplingTree = jsonObject.getString(Checkpoint.KEY_SAPLING_TREE)
            val orchardTree =
                try {
                    jsonObject.getString(Checkpoint.KEY_ORCHARD_TREE)
                } catch (e: JSONException) {
                    Twig.warn(e) { "This checkpoint does not contain an Orchard tree state" }
                    // For checkpoints that don't contain an Orchard tree state, we can use
                    // the empty Orchard tree state as long as the height is before NU5.
                    require(height < zcashNetwork.orchardActivationHeight) {
                        "Post-NU5 checkpoint at height $height missing orchardTree field"
                    }
                    "000000" // NON-NLS
                }

            return Checkpoint(height, hash, epochSeconds, saplingTree, orchardTree)
        }

        else -> {
            throw IllegalArgumentException("Unsupported version $version")
        }
    }
}
