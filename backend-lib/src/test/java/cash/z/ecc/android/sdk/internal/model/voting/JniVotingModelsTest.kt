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

    @Test
    fun van_witness_constructor_matches_rust_jni_signature() {
        val constructor =
            JniVanWitness::class.java.getDeclaredConstructor(
                Array<ByteArray>::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )

        assertEquals(
            "([[BJJ)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun vote_commitment_result_constructor_matches_rust_jni_signature() {
        val constructor =
            JniVoteCommitmentResult::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                Array<JniWireEncryptedShare>::class.java,
                Long::class.javaPrimitiveType,
                String::class.java,
                ByteArray::class.java,
                Array<ByteArray>::class.java,
                Array<ByteArray>::class.java,
                ByteArray::class.java,
                ByteArray::class.java
            )

        assertEquals(
            "([B[B[BI[B[Lcash/z/ecc/android/sdk/internal/model/voting/" +
                "JniWireEncryptedShare;JLjava/lang/String;[B[[B[[B[B[B)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun commitment_bundle_record_constructor_matches_rust_jni_signature() {
        val constructor =
            JniCommitmentBundleRecord::class.java.getDeclaredConstructor(
                JniVoteCommitmentResult::class.java,
                Long::class.javaPrimitiveType
            )

        assertEquals(
            "(Lcash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitmentResult;J)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun share_payload_constructor_matches_rust_jni_signature() {
        val constructor =
            JniSharePayload::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                JniWireEncryptedShare::class.java,
                Long::class.javaPrimitiveType,
                Array<JniWireEncryptedShare>::class.java,
                Array<ByteArray>::class.java,
                ByteArray::class.java
            )

        assertEquals(
            "([BIILcash/z/ecc/android/sdk/internal/model/voting/" +
                "JniWireEncryptedShare;J[Lcash/z/ecc/android/sdk/internal/model/voting/" +
                "JniWireEncryptedShare;[[B[B)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun share_delegation_record_constructor_matches_rust_jni_signature() {
        val constructor =
            JniShareDelegationRecord::class.java.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Array<String>::class.java,
                ByteArray::class.java,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )

        assertEquals(
            "(Ljava/lang/String;III[Ljava/lang/String;[BZJJ)V",
            constructor.jniDescriptor()
        )
    }

    private fun java.lang.reflect.Constructor<*>.jniDescriptor() =
        parameterTypes.joinToString(prefix = "(", postfix = ")V", separator = "") { parameter ->
            parameter.jniDescriptor()
        }

    private fun Class<*>.jniDescriptor(): String =
        when {
            isArray -> "[${requireNotNull(componentType).jniDescriptor()}"
            this == java.lang.Byte.TYPE -> "B"
            this == java.lang.Boolean.TYPE -> "Z"
            this == java.lang.Integer.TYPE -> "I"
            this == java.lang.Long.TYPE -> "J"
            isPrimitive -> error("Unsupported JNI primitive parameter: $name")
            else -> "L${name.replace('.', '/')};"
        }
}
