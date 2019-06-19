package cash.z.wallet.sdk.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import cash.z.wallet.sdk.entity.Transaction

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE id_tx = :id")
    fun findById(id: Long): Transaction?

    @Delete
    fun delete(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id_tx = :id")
    fun deleteById(id: Long)

    /**
     * Query transactions, aggregating information on send/receive, sorted carefully so the newest data is at the top
     * and the oldest transactions are at the bottom.
     */
    @Query("""
        SELECT transactions.id_tx             AS txId,
               transactions.block             AS height,
               transactions.raw IS NOT NULL   AS isSend,
               transactions.block IS NOT NULL AS isMined,
               blocks.time                    AS timeInSeconds,
               sent_notes.address             AS address,
               CASE
                 WHEN transactions.raw IS NOT NULL THEN sent_notes.value
                 ELSE received_notes.value
               END                            AS value,
              CASE
                 WHEN transactions.raw IS NOT NULL THEN sent_notes.memo IS NOT NULL
                 ELSE received_notes.memo IS NOT NULL
               END                            AS rawMemoExists,
             CASE
                 WHEN transactions.raw IS NOT NULL THEN sent_notes.id_note
                 ELSE received_notes.id_note
               END                            AS noteId
        FROM   transactions
               LEFT JOIN sent_notes
                      ON transactions.id_tx = sent_notes.tx
               LEFT JOIN received_notes
                      ON transactions.id_tx = received_notes.tx
               LEFT JOIN blocks
                      ON transactions.block = blocks.height
        WHERE received_notes.is_change != 1 or transactions.raw IS NOT NULL
        ORDER  BY block IS NOT NUll, height DESC, time DESC, txId DESC
    """)
    fun getAll(): List<WalletTransaction>
}

data class WalletTransaction(
    val noteId: Long = 0L,
    val txId: Long = 0L,
    val value: Long = 0L,
    val height: Int? = null,
    val isSend: Boolean = false,
    val timeInSeconds: Long = 0L,
    val address: String? = null,
    val isMined: Boolean = false,
    // does the raw transaction contain a memo?
    val rawMemoExists: Boolean = false,
    // TODO: investigate populating this with SQL rather than a separate SDK call. then get rid of rawMemoExists.
    var memo: String? = null,
    // set/maintain a custom status
    var status: String? = null
)