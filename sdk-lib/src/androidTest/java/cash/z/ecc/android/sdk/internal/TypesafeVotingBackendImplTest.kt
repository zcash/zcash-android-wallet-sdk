package cash.z.ecc.android.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
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

    private fun repeatedHex(
        byteValue: Int,
        size: Int
    ) = "%02x".format(byteValue).repeat(size)

    private companion object {
        private const val PROTOCOL_FIELD_BYTES = 32
        private const val PROOF_BYTES = 3
    }
}
