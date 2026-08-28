package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.flow.Flow

/**
 * Declaration of a host-provided interface, reproduced so extensions can compile
 * against it. **Not** an implementation, and never packaged into the APK.
 *
 * [AnimeSource.getVideoList] is one suspending call returning one finished list.
 * A source resolving several independent backends in parallel has to join them
 * all before returning, so a stream that was ready in under a second is withheld
 * until the slowest backend finishes. There is no way to report partial progress
 * through that signature.
 *
 * A host that understands this interface tests for it with `is`, exactly as it
 * already does for [ConfigurableAnimeSource], and calls [getVideoListFlow]
 * instead. A host that does not keeps using [AnimeSource.getVideoList] and is
 * unaffected.
 *
 * ## Why this file exists here
 *
 * `aniyomi-extensions-lib` does not ship this interface, so there is nothing to
 * compile against without declaring it. It lives in a `compileOnly` module for
 * the same reason the rest of the source ABI is `compileOnly`: the class must be
 * *referenced* by the extension dex and *defined* only by the host, so the
 * `is` check compares against the host's own class rather than a duplicate.
 *
 * Packaging a copy would be worse than useless - the host's `ProgressiveVideoSource`
 * and a bundled one are different types to the classloader, the `is` check would
 * fail, and the extension would silently fall back to the blocking path.
 *
 * Kept byte-compatible with the host declaration: same package, same name, same
 * single method and signature. Changing any of those breaks resolution at runtime.
 *
 * ## Contract
 *
 * - Emit at least once. A source with nothing to offer emits an empty list rather
 *   than completing silently, so the host can tell "none found" from "still
 *   working".
 * - Each emission is **cumulative**: it carries every stream found so far, in the
 *   order the host should offer them. Emitting only new entries would make every
 *   host reimplement merging.
 * - Later emissions may reorder earlier entries, but an entry already emitted
 *   must not disappear - the host may already be playing it.
 * - One backend failing is not failure of the flow. Emit what the others found;
 *   throwing discards work that was already usable.
 */
interface ProgressiveVideoSource : AnimeSource {

    /**
     * Streams for [episode], emitted as they are found.
     *
     * Each emission supersedes the last. The flow completes when the source has
     * nothing further to add.
     */
    fun getVideoListFlow(episode: SEpisode): Flow<List<Video>>
}
