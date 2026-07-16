package cash.z.ecc.android.sdk.internal.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LazyTorClientTest {
    @Test
    fun getOrCreate_after_dispose_throws() =
        runTest {
            val lazyTorClient = LazyTorClient { error("factory should not be invoked once disposed") }

            lazyTorClient.dispose()

            assertFailsWith<IllegalStateException> { lazyTorClient.getOrCreate() }
        }

    @Test
    fun dispose_is_idempotent() =
        runTest {
            val lazyTorClient = LazyTorClient { error("factory should not be invoked once disposed") }

            lazyTorClient.dispose()
            lazyTorClient.dispose()

            assertFailsWith<IllegalStateException> { lazyTorClient.getOrCreate() }
        }

    @Test
    fun getOrCreate_failure_does_not_cache_and_factory_is_retried() =
        runTest {
            var factoryCallCount = 0
            val lazyTorClient =
                LazyTorClient {
                    factoryCallCount++
                    error("boom $factoryCallCount")
                }

            assertFailsWith<IllegalStateException> { lazyTorClient.getOrCreate() }
            assertFailsWith<IllegalStateException> { lazyTorClient.getOrCreate() }

            assertEquals(2, factoryCallCount)
        }
}
