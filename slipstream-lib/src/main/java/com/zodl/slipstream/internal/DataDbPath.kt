package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.model.ZcashNetwork
import java.io.File

/**
 * The D5-critical path formula (`SDK_ADAPTER_PLAN.md` T10, R57 `getExistingDataDbFilePath` /
 * `getWalletDbPathForVoting` / C3 `erase`) - MUST match the upstream SDK's
 * `DatabaseCoordinator`/`Files`/`DB_DATA_NAME` byte-for-byte, or flag-flip breaks:
 * `<no_backup>/co.electricoin.zcash/<aliasPrefix><networkName>_data.sqlite3`, `aliasPrefix =
 * alias.endsWith('_') ? alias : "${alias}_"`, `networkName` = lowercase network name. Ships as a
 * pure `File`-returning function (the no-backup root injected as a plain [File]) so it is
 * JVM-testable without an Android context (`SDK_ADAPTER_PLAN.md` law 5).
 */
internal object DataDbPath {
    private const val NO_BACKUP_SUBDIRECTORY = "co.electricoin.zcash"
    private const val DB_DATA_NAME = "data.sqlite3"

    fun dataDbFile(
        noBackupRoot: File,
        alias: String,
        network: ZcashNetwork
    ): File {
        val aliasPrefix = if (alias.endsWith("_")) alias else "${alias}_"
        val networkName = network.networkName.lowercase()
        return File(File(noBackupRoot, NO_BACKUP_SUBDIRECTORY), "$aliasPrefix${networkName}_$DB_DATA_NAME")
    }
}
