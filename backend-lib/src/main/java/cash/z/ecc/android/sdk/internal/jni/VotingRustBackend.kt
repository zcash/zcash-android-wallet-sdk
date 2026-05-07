package cash.z.ecc.android.sdk.internal.jni

class VotingRustBackend private constructor() {
    @Throws(RuntimeException::class)
    fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray = computeShareNullifierNative(voteCommitment, shareIndex, blind)

    companion object {
        suspend fun new(): VotingRustBackend {
            RustBackend.loadLibrary()

            return VotingRustBackend()
        }

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeShareNullifierNative(
            voteCommitment: ByteArray,
            shareIndex: Int,
            blind: ByteArray
        ): ByteArray
    }
}
