package uk.crownmedia.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
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

    @Test
    fun threeIndependentCatalogCountsCanHydrateInParallel() = runBlocking {
        val coordinator = CatalogWorkCoordinator()
        val started = List(3) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()

        val jobs = started.map { signal ->
            async {
                coordinator.background {
                    signal.complete(Unit)
                    release.await()
                }
            }
        }

        withTimeout(1_000) { started.forEach { it.await() } }
        release.complete(Unit)
        jobs.awaitAll()
        Unit
    }
}
