package cash.z.ecc.android.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TypesafeVotingBackendTest {
    @Test
    fun votingNoteInfo_to_string_omits_note_secrets() {
        val text =
            VotingNoteInfo(
                commitment = byteArrayOf(1),
                nullifier = byteArrayOf(2),
                value = 3,
                position = 4,
                diversifier = byteArrayOf(5),
                rho = byteArrayOf(6),
                rseed = byteArrayOf(7),
                scope = VotingNoteScope.EXTERNAL,
                ufvk = "ufvk-fixture"
            ).toString()

        assertEquals("VotingNoteInfo(redacted)", text)
        assertFalse(text.contains("ufvk-fixture"))
    }
}
