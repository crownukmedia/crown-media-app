package uk.crownmedia.app

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Keeps background catalog counting/indexing from occupying the interactive refresh lane. */
internal class CatalogWorkCoordinator {
    private val interactivePermit = Semaphore(1)
    // Live, Movies, and Series are independent provider endpoints. Let their background
    // hydration overlap while keeping each kind single-flight in MainActivity. This avoids
    // serial multi-minute count loading without ever occupying the interactive lane.
    private val backgroundPermit = Semaphore(3)

    suspend fun <T> interactive(block: suspend () -> T): T = interactivePermit.withPermit { block() }

    suspend fun <T> background(block: suspend () -> T): T = backgroundPermit.withPermit { block() }
}
