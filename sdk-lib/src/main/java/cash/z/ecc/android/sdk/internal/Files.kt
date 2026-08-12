package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.ext.canWriteSuspend
import cash.z.ecc.android.sdk.internal.ext.existsSuspend
import cash.z.ecc.android.sdk.internal.ext.getNoBackupFilesDirSuspend
import cash.z.ecc.android.sdk.internal.ext.mkdirsSuspend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Because the filesystem is a shared resource, this declares the filenames that the SDK is using
 * in one centralized place.
 */
internal object Files {
    /**
     * Subdirectory under the Android "no backup" directory which is owned by the SDK.
     */
    const val NO_BACKUP_SUBDIRECTORY = "co.electricoin.zcash" // $NON-NLS

    /**
     * Subdirectory under [NO_BACKUP_SUBDIRECTORY] for Tor client data.
     */
    const val TOR_SUBDIR = "tor"

    private val accessMutex = Mutex()

    /**
     * @return Subdirectory of the "no_backup" directory that is owned by the SDK.  The returned
     * directory will exist when this method returns.
     *
     * As we use a suspend version of the file operations here, we protect the operations with mutex
     * to prevent multiple threads to invoke the function at the same time.
     */
    suspend fun getZcashNoBackupSubdirectory(context: Context): File {
        val dir = File(context.getNoBackupFilesDirSuspend(), NO_BACKUP_SUBDIRECTORY)

        accessMutex.withLock {
            if (!dir.existsSuspend()) {
                if (!dir.mkdirsSuspend()) {
                    error("${dir.absolutePath} directory does not exist and could not be created")
                }
            }

            if (!dir.canWriteSuspend()) {
                error("${dir.absolutePath} directory is not writable")
            }
        }
        return dir
    }

    suspend fun getTorDir(context: Context): File = File(getZcashNoBackupSubdirectory(context), TOR_SUBDIR)
}
