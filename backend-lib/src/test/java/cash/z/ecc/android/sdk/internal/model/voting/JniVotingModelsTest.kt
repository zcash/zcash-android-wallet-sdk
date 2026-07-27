package cash.z.ecc.android.sdk.internal.model.voting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JniVotingModelsTest {
    @Test
    fun vote_commit_result_to_string_omits_commitment_details() {
        val text =
            JniVoteCommitResult(
                vanNullifier = byteArrayOf(1),
                voteAuthorityNoteNew = byteArrayOf(2),
                voteCommitment = byteArrayOf(3),
                proposalId = 4,
                bundleIndex = 5,
                proof = byteArrayOf(5),
                anchorHeight = 8,
                rVpk = byteArrayOf(102),
                voteAuthSig = byteArrayOf(103),
                encShares = listOf(JniWireEncryptedShare(byteArrayOf(6), byteArrayOf(7), 0)),
                sharePayloads = emptyList()
            ).toString()

        assertEquals("JniVoteCommitResult(redacted)", text)
        assertFalse(text.contains("proposalId"))
        assertFalse(text.contains("rVpk"))
        assertFalse(text.contains("voteAuthSig"))
        assertFalse(text.contains("102"))
        assertFalse(text.contains("103"))
    }

    @Test
    fun vote_submission_to_string_omits_submission_details() {
        val text =
            JniVoteSubmission(
                voteRoundId = "round",
                proposalId = 1,
                bundleIndex = 2,
                vanNullifier = byteArrayOf(3),
                voteAuthorityNoteNew = byteArrayOf(4),
                voteCommitment = byteArrayOf(5),
                proof = byteArrayOf(6),
                rVpk = byteArrayOf(102),
                voteAuthSig = byteArrayOf(103),
                anchorHeight = 7
            ).toString()

        assertEquals("JniVoteSubmission(redacted)", text)
        assertFalse(text.contains("round"))
        assertFalse(text.contains("rVpk"))
        assertFalse(text.contains("102"))
        assertFalse(text.contains("103"))
    }

    // The stored secret is unrecoverable app-owned key material. A generated data-class
    // toString() would print its bytes into any log line that interpolates the hotkey.
    @Test
    fun voting_hotkey_to_string_omits_the_stored_secret() {
        val text =
            JniVotingHotkey(
                storedSecret = ByteArray(64) { 0x7B },
                rawOrchardAddress = ByteArray(43) { 0x2C },
                addressIndex = 0
            ).toString()

        assertEquals("JniVotingHotkey(redacted)", text)
        assertFalse(text.contains("storedSecret"))
        assertFalse(text.contains("123"))
        assertFalse(text.contains("7b"))
    }

    // A note carries the randomness that reconstructs its spend and the account's full viewing
    // key. A generated data-class toString() would print both into any log line that interpolates
    // a note, so the redaction is asserted rather than left to survive by convention.
    @Test
    fun note_info_to_string_omits_note_secrets_and_the_viewing_key() {
        val text =
            JniNoteInfo(
                commitment = byteArrayOf(1),
                nullifier = byteArrayOf(2),
                value = 100,
                position = 7,
                diversifier = byteArrayOf(3),
                rho = byteArrayOf(4),
                rseed = ByteArray(32) { 0x5A },
                scope = 0,
                ufvk = "uview1exampleviewingkey"
            ).toString()

        assertEquals("JniNoteInfo(redacted)", text)
        assertFalse(text.contains("rseed"))
        assertFalse(text.contains("uview1exampleviewingkey"))
        assertFalse(text.contains("90"))
    }

    // The primary blind opens the vote commitment, so printing it would disclose the plaintext
    // vote of an otherwise-shielded ballot.
    @Test
    fun share_payload_to_string_omits_the_primary_blind() {
        val text =
            JniSharePayload(
                sharesHash = byteArrayOf(1),
                proposalId = 2,
                voteDecision = 3,
                encShare = JniWireEncryptedShare(byteArrayOf(4), byteArrayOf(5), 0),
                treePosition = 6,
                allEncShares = emptyList(),
                shareComms = emptyList(),
                primaryBlind = ByteArray(32) { 0x6B }
            ).toString()

        assertEquals("JniSharePayload(redacted)", text)
        assertFalse(text.contains("primaryBlind"))
        assertFalse(text.contains("107"))
        assertFalse(text.contains("voteDecision"))
    }

    // Logging a share nullifier next to the round and proposal it belongs to is exactly the
    // linkage the helper-share protocol exists to prevent.
    @Test
    fun share_delegation_record_to_string_omits_the_share_nullifier() {
        val text =
            JniShareDelegationRecord(
                roundId = "round",
                bundleIndex = 1,
                proposalId = 2,
                shareIndex = 3,
                sentToUrls = listOf("https://helper.example"),
                nullifier = ByteArray(32) { 0x7D },
                confirmed = false,
                submitAt = 4,
                createdAt = 5
            ).toString()

        assertEquals("JniShareDelegationRecord(redacted)", text)
        assertFalse(text.contains("nullifier"))
        assertFalse(text.contains("125"))
        assertFalse(text.contains("round"))
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
    fun vote_commit_result_constructor_matches_rust_jni_signature() {
        val constructor =
            JniVoteCommitResult::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java,
                Array<JniWireEncryptedShare>::class.java,
                Array<JniSharePayload>::class.java
            )

        assertEquals(
            "([B[B[BII[BJ[B[B[Lcash/z/ecc/android/sdk/internal/model/voting/" +
                "JniWireEncryptedShare;[Lcash/z/ecc/android/sdk/internal/model/voting/" +
                "JniSharePayload;)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun vote_submission_constructor_matches_rust_jni_signature() {
        val constructor =
            JniVoteSubmission::class.java.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                Long::class.javaPrimitiveType
            )

        assertEquals(
            "(Ljava/lang/String;II[B[B[B[B[B[BJ)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun voting_hotkey_constructor_matches_rust_jni_signature() {
        val constructor =
            JniVotingHotkey::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )

        assertEquals(
            "([B[BI)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun commitment_bundle_record_constructor_matches_rust_jni_signature() {
        val constructor =
            JniCommitmentBundleRecord::class.java.getDeclaredConstructor(
                JniVoteCommitResult::class.java,
                Long::class.javaPrimitiveType
            )

        assertEquals(
            "(Lcash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitResult;J)V",
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
