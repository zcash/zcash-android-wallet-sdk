package cash.z.ecc.android.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VotingNoteInfoTest {
    // A note carries the randomness that reconstructs its spend and the account's full viewing
    // key. A generated data-class toString() would print both into any log line that interpolates
    // a note, so the redaction is asserted rather than left to survive by convention.
    @Test
    fun voting_note_info_to_string_omits_note_secrets_and_the_viewing_key() {
        val text =
            VotingNoteInfo(
                commitment = byteArrayOf(1),
                nullifier = byteArrayOf(2),
                value = 100,
                position = 7,
                diversifier = byteArrayOf(3),
                rho = byteArrayOf(4),
                rseed = ByteArray(32) { 0x5A },
                scope = VotingNoteScope.EXTERNAL,
                ufvk = "uview1exampleviewingkey"
            ).toString()

        assertEquals("VotingNoteInfo(redacted)", text)
        assertFalse(text.contains("rseed"))
        assertFalse(text.contains("uview1exampleviewingkey"))
        assertFalse(text.contains("90"))
    }
}
