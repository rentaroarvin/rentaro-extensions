package eu.kanade.tachiyomi.animeextension.en.arvindf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Runs every task at once and returns the first non-null result, cancelling the rest. A task that
 * throws or returns null only drops out; null comes back when none produce a value.
 *
 * The resolver pools answer at very different speeds (one NetShort host took 13 seconds while another
 * took half of one), so waiting for all of them, or trying them in turn, would make the slowest host
 * set the pace.
 */
internal suspend fun <T : Any> firstSuccess(tasks: List<suspend () -> T?>): T? = coroutineScope {
    val running = tasks.map { task ->
        async {
            try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val pending = running.toMutableList()
    var winner: T? = null
    while (winner == null && pending.isNotEmpty()) {
        val (finished, value) = select<Pair<Deferred<T?>, T?>> {
            pending.forEach { deferred -> deferred.onAwait { deferred to it } }
        }
        pending.remove(finished)
        winner = value
    }
    running.forEach { it.cancel() }
    winner
}
