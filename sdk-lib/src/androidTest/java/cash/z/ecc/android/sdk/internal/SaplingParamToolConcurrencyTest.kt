package cash.z.ecc.android.sdk.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import cash.z.ecc.android.sdk.internal.ext.existsSuspend
import cash.z.ecc.android.sdk.internal.ext.getSha1Hash
import cash.z.ecc.android.sdk.test.getAppContext
import cash.z.ecc.fixture.SaplingParamsFixture
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertTrue

/**
 * Regression test for PRO-97 (`TransactionNotCreatedException: parameter file size is not
 * correct`). Root cause was a missing lock around [SaplingParamTool.fetchParams]: concurrent
 * callers raced on the same static temporary file name (`_sapling-output.params`), and one
 * caller's success/failure cleanup could delete or clobber another's in-flight download,
 * transiently leaving the final param file missing or invalid. Fixed by serializing
 * [SaplingParamTool.fetchParams] with `fetchParamsMutex`.
 *
 * Ignored for the same reason as [SaplingParamToolIntegrationTest]: it hits the real network
 * (download.z.cash) and is prone to CI flakiness. Run manually when touching params-fetch logic.
 */
@Ignore(
    "Hits the real network (download.z.cash) with several concurrent downloads per round, same CI " +
        "flakiness risk as SaplingParamToolIntegrationTest. Run manually when touching params-fetch " +
        "concurrency."
)
@RunWith(AndroidJUnit4::class)
class SaplingParamToolConcurrencyTest {
    // Number of concurrent coroutines racing to fetch the very same param file in one round.
    private val concurrencyLevel = 4

    // Number of rounds repeated, since this is a TOCTOU-style race and a single attempt might not
    // trigger it.
    private val rounds = 3

    private val outputSaplingParams =
        SaplingParamsFixture.new(
            SaplingParamsFixture.DESTINATION_DIRECTORY,
            SaplingParamsFixture.OUTPUT_FILE_NAME,
            SaplingParamsFixture.OUTPUT_FILE_MAX_SIZE,
            SaplingParamsFixture.OUTPUT_FILE_HASH
        )

    @Before
    fun setup() {
        runBlocking {
            SaplingParamsFixture.clearAllFilesFromDirectory(SaplingParamsFixture.DESTINATION_DIRECTORY)
            SaplingParamsFixture.clearAllFilesFromDirectory(SaplingParamsFixture.DESTINATION_DIRECTORY_LEGACY)
        }
    }

    @Test
    @LargeTest
    fun concurrent_fetch_params_same_file_race_test() =
        runBlocking {
            val saplingParamTool = SaplingParamTool.new(getAppContext())

            val outcomesPerRound = mutableListOf<List<Result<Unit>>>()
            val finalStatePerRound = mutableListOf<Triple<Boolean, Boolean, String>>()

            repeat(rounds) { round ->
                // Fresh directory state for every round.
                SaplingParamsFixture.clearAllFilesFromDirectory(SaplingParamsFixture.DESTINATION_DIRECTORY)

                val results: List<Result<Unit>> =
                    coroutineScope {
                        List(concurrencyLevel) {
                            async {
                                runCatching {
                                    saplingParamTool.fetchParams(outputSaplingParams)
                                }
                            }
                        }.awaitAll()
                    }
                outcomesPerRound += results

                val finalFile =
                    File(
                        SaplingParamsFixture.DESTINATION_DIRECTORY,
                        SaplingParamsFixture.OUTPUT_FILE_NAME
                    )
                val exists = finalFile.existsSuspend()
                val hashValid =
                    exists &&
                        runCatching { finalFile.getSha1Hash() == SaplingParamsFixture.OUTPUT_FILE_HASH }
                            .getOrDefault(false)
                val sizeReported = if (exists) finalFile.length().toString() else "N/A"

                finalStatePerRound += Triple(exists, hashValid, sizeReported)

                val successCount = results.count { it.isSuccess }
                val failureTypes = results.mapNotNull { it.exceptionOrNull()?.let { e -> e::class.simpleName } }

                println(
                    "PRO97_RACE_ROUND[$round]: successes=$successCount/${results.size} " +
                        "failures=$failureTypes finalFileExists=$exists finalFileHashValid=$hashValid " +
                        "finalFileSize=$sizeReported"
                )
            }

            // Summarize everything at the end for easy grepping from logcat/test output.
            outcomesPerRound.forEachIndexed { round, results ->
                results.forEachIndexed { idx, result ->
                    result.onFailure { e ->
                        println(
                            "PRO97_RACE_ROUND[$round]_TASK[$idx]_EXCEPTION: ${e::class.qualifiedName}: " +
                                "${e.message}"
                        )
                    }
                }
            }

            val roundsWithInvalidFinalState =
                finalStatePerRound.withIndex().filter { (_, state) ->
                    val (exists, hashValid, _) = state
                    !exists || !hashValid
                }

            println(
                "PRO97_RACE_SUMMARY: roundsWithInvalidFinalState=${roundsWithInvalidFinalState.map { it.index }} " +
                    "totalRounds=$rounds concurrencyLevel=$concurrencyLevel"
            )

            assertTrue(
                roundsWithInvalidFinalState.isEmpty(),
                "Param file ended up missing/invalid after concurrent fetchParams() in round(s) " +
                    "${roundsWithInvalidFinalState.map { it.index }} - see PRO97_RACE_ROUND logs above."
            )
        }
}
