package uk.crownmedia.app

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Keeps background catalog counting/indexing from occupying the interactive refresh lane. */
internal class CatalogWorkCoordinator {
    private val interactivePermit = Semaphore(1)
    private val backgroundPermit = Semaphore(1)

    suspend fun <T> interactive(block: suspend () -> T): T = interactivePermit.withPermit { block() }

    suspend fun <T> background(block: suspend () -> T): T = backgroundPermit.withPermit { block() }
}
