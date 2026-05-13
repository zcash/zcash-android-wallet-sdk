package cash.z.ecc.android.sdk.internal.model.voting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JniVotingModelsTest {
    @Test
    fun vote_commitment_result_to_string_redacts_signing_material() {
        val text =
            JniVoteCommitmentResult(
                vanNullifier = byteArrayOf(1),
                voteAuthorityNoteNew = byteArrayOf(2),
                voteCommitment = byteArrayOf(3),
                proposalId = 4,
                proof = byteArrayOf(5),
                encShares = listOf(JniWireEncryptedShare(byteArrayOf(6), byteArrayOf(7), 0)),
                anchorHeight = 8,
                voteRoundId = "round",
                sharesHash = byteArrayOf(9),
                shareBlinds = listOf(byteArrayOf(101)),
                shareComms = listOf(byteArrayOf(10)),
                rVpk = byteArrayOf(102),
                alphaV = byteArrayOf(103)
            ).toString()

        assertTrue(text.contains("shareBlinds=***"))
        assertTrue(text.contains("rVpk=***"))
        assertTrue(text.contains("alphaV=***"))
        assertFalse(text.contains("101"))
        assertFalse(text.contains("102"))
        assertFalse(text.contains("103"))
    }

    @Test
    fun note_info_constructor_matches_rust_jni_signature() {
        val constructor =
            JniNoteInfo::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )

        assertEquals(
            "([B[BJJ[B[B[BILjava/lang/String;)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun witness_data_constructor_matches_rust_jni_signature() {
        val constructor =
            JniWitnessData::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
                Array<ByteArray>::class.java
            )

        assertEquals(
            "([BJ[B[[B)V",
            constructor.jniDescriptor()
        )
    }

    private fun java.lang.reflect.Constructor<*>.jniDescriptor() =
        parameterTypes.joinToString(prefix = "(", postfix = ")V", separator = "") { parameter ->
            parameter.jniDescriptor()
        }

    private fun Class<*>.jniDescriptor(): String =
        when (this) {
            java.lang.Long.TYPE -> "J"
            java.lang.Integer.TYPE -> "I"
            ByteArray::class.java -> "[B"
            Array<ByteArray>::class.java -> "[[B"
            String::class.java -> "Ljava/lang/String;"
            else -> error("Unsupported JNI constructor parameter: $name")
        }
}
