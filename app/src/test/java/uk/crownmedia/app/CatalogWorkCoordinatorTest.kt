package uk.crownmedia.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogWorkCoordinatorTest {
    @Test
    fun backgroundCountingNeverOccupiesInteractiveRefreshLane() = runBlocking {
        val coordinator = CatalogWorkCoordinator()
        val backgroundStarted = CompletableDeferred<Unit>()
        val releaseBackground = CompletableDeferred<Unit>()
        val background = launch {
            coordinator.background {
                backgroundStarted.complete(Unit)
                releaseBackground.await()
            }
        }
        backgroundStarted.await()

        val result = withTimeout(1_000) {
            coordinator.interactive { "content-ready" }
        }

        assertEquals("content-ready", result)
        releaseBackground.complete(Unit)
        background.join()
    }
}
