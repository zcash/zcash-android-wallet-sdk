package cash.z.wallet.sdk.block

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import cash.z.wallet.sdk.dao.CompactBlockDao
import cash.z.wallet.sdk.db.CompactBlockDb
import cash.z.wallet.sdk.entity.CompactBlock
import cash.z.wallet.sdk.ext.SAPLING_ACTIVATION_HEIGHT
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class CompactBlockDbStore(
    applicationContext: Context,
    cacheDbName: String
) : CompactBlockStore {

    private val cacheDao: CompactBlockDao
    private val cacheDb: CompactBlockDb

    init {
        cacheDb = createCompactBlockCacheDb(applicationContext, cacheDbName)
        cacheDao = cacheDb.complactBlockDao()
    }

    private fun createCompactBlockCacheDb(applicationContext: Context, cacheDbName: String): CompactBlockDb {
        return Room.databaseBuilder(applicationContext, CompactBlockDb::class.java, cacheDbName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            // this is a simple cache of blocks. destroying the db should be benign
            .fallbackToDestructiveMigration()
            .build()
    }

    override suspend fun getLatestHeight(): Int = withContext(IO) {
        val lastBlock = Math.max(0, cacheDao.latestBlockHeight() - 1)
        if (lastBlock < SAPLING_ACTIVATION_HEIGHT) -1 else lastBlock
    }

    override suspend fun write(result: List<CompactBlock>) = withContext(IO) {
        cacheDao.insert(result)
    }

    override suspend fun rewindTo(height: Int) = withContext(IO) {
        cacheDao.rewindTo(height)
    }
}