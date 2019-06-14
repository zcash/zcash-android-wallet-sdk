package cash.z.wallet.sdk.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cash.z.wallet.sdk.entity.CompactBlock

@Dao
interface CompactBlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(block: CompactBlock)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(block: List<CompactBlock>)

    @Query("DELETE FROM compactblocks WHERE height >= :height")
    fun rewindTo(height: Int)

    @Query("SELECT MAX(height) FROM compactblocks")
    fun latestBlockHeight(): Int
}