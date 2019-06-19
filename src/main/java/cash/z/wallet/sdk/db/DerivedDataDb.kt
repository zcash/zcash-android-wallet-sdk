package cash.z.wallet.sdk.db

import androidx.room.Database
import androidx.room.RoomDatabase
import cash.z.wallet.sdk.dao.BlockDao
import cash.z.wallet.sdk.dao.TransactionDao
import cash.z.wallet.sdk.entity.*

@Database(
    entities = [
        Transaction::class,
        Block::class,
        Note::class,
        Account::class,
        Sent::class
    ],
    version = 3,
    exportSchema = false
)
abstract class DerivedDataDb : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun blockDao(): BlockDao
}