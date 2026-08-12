package cash.z.ecc.android.sdk.internal.ext

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun Context.getDatabasePathSuspend(fileName: String) =
    withContext(Dispatchers.IO) {
        getDatabasePath(fileName)
    }

internal suspend fun Context.getNoBackupFilesDirSuspend() = withContext(Dispatchers.IO) { noBackupFilesDir }

internal suspend fun Context.getCacheDirSuspend() = withContext(Dispatchers.IO) { cacheDir }

internal suspend fun Context.getFilesDirSuspend() = withContext(Dispatchers.IO) { filesDir }

internal suspend fun Context.getDataDirCompatSuspend() =
    withContext(Dispatchers.IO) { ContextCompat.getDataDir(this@getDataDirCompatSuspend) }
