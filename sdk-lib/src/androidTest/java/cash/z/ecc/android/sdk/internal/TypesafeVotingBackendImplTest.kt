package cash.z.ecc.android.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class TypesafeVotingBackendImplTest {
    @Test
    fun delegation_proof_parser_checks_public_input_element_lengths() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                delegationProofJson(
                    publicInputs = JSONArray().put(repeatedHex(1, PROTOCOL_FIELD_BYTES - 1))
                ).toDelegationProofResult()
            }

        assertTrue(error.message.orEmpty().contains("public_inputs[0]"))
    }

    @Test
    fun delegation_submission_parser_checks_gov_nullifier_element_lengths() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                delegationSubmissionJson(
                    govNullifiers = JSONArray().put(repeatedHex(2, PROTOCOL_FIELD_BYTES - 1))
                ).toDelegationSubmissionResult()
            }

        assertTrue(error.message.orEmpty().contains("gov_nullifiers[0]"))
    }

    @Test
    fun delegation_submission_parser_accepts_expected_byte_lengths() {
        val result =
            delegationSubmissionJson(
                proof = repeatedHex(3, PROOF_BYTES),
                govNullifiers = JSONArray().put(repeatedHex(4, PROTOCOL_FIELD_BYTES))
            ).toDelegationSubmissionResult()

        assertEquals(PROOF_BYTES, result.proof.size)
        assertEquals(SPEND_AUTH_SIG_BYTES, result.spendAuthSig.size)
        assertEquals(1, result.govNullifiers.size)
        assertContentEquals(ByteArray(PROTOCOL_FIELD_BYTES) { 4 }, result.govNullifiers.first())
    }

    private fun delegationProofJson(
        publicInputs: JSONArray = JSONArray().put(repeatedHex(1, PROTOCOL_FIELD_BYTES)),
        govNullifiers: JSONArray = JSONArray().put(repeatedHex(2, PROTOCOL_FIELD_BYTES))
    ) = JSONObject()
        .put("proof", repeatedHex(3, PROOF_BYTES))
        .put("public_inputs", publicInputs)
        .put("nf_signed", repeatedHex(4, PROTOCOL_FIELD_BYTES))
        .put("cmx_new", repeatedHex(5, PROTOCOL_FIELD_BYTES))
        .put("gov_nullifiers", govNullifiers)
        .put("van_comm", repeatedHex(6, PROTOCOL_FIELD_BYTES))
        .put("rk", repeatedHex(7, PROTOCOL_FIELD_BYTES))

    private fun delegationSubmissionJson(
        proof: String = repeatedHex(3, PROOF_BYTES),
        govNullifiers: JSONArray = JSONArray().put(repeatedHex(2, PROTOCOL_FIELD_BYTES))
    ) = JSONObject()
        .put("proof", proof)
        .put("rk", repeatedHex(7, PROTOCOL_FIELD_BYTES))
        .put("spend_auth_sig", repeatedHex(8, SPEND_AUTH_SIG_BYTES))
        .put("sighash", repeatedHex(9, PROTOCOL_FIELD_BYTES))
        .put("nf_signed", repeatedHex(4, PROTOCOL_FIELD_BYTES))
        .put("cmx_new", repeatedHex(5, PROTOCOL_FIELD_BYTES))
        .put("gov_comm", repeatedHex(6, PROTOCOL_FIELD_BYTES))
        .put("gov_nullifiers", govNullifiers)
        .put("alpha", repeatedHex(10, PROTOCOL_FIELD_BYTES))
        .put("vote_round_id", "round-1")

    private fun repeatedHex(
        byteValue: Int,
        size: Int
    ) = "%02x".format(byteValue).repeat(size)

    private companion object {
        private const val PROTOCOL_FIELD_BYTES = 32
        private const val SPEND_AUTH_SIG_BYTES = 64
        private const val PROOF_BYTES = 3
    }
}
