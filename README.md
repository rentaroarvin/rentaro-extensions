# Rentaro Extensions

Source code for a personal Aniyomi / Anikku extension. Metadata comes from
TMDB; playback is resolved through the Videasy backend.

> Personal hobby repo. Not affiliated with Aniyomi, Mihon, or any content
> provider, and not intended for public distribution.

## Installation

Add the extension repo URL in your app under
**Settings → Browse → Extension repos → Add**:

```
https://raw.githubusercontent.com/rentaroarvin/rentaro-repo/repo/index.min.json
```

Then go to **Browse → Extensions** and install **Rentaro**. Enable
**Show NSFW sources** in settings if the extension does not appear, since it is
flagged `nsfw=1`.

Supported clients: Aniyomi, Anikku, and forks that read the Tachiyomi-style
extension index.

APKs can also be installed manually from
[`apk/`](https://github.com/rentaroarvin/rentaro-repo/tree/repo/apk).

## Repositories

| Repo | Branch | Purpose |
| --- | --- | --- |
| [rentaro-extensions](https://github.com/rentaroarvin/rentaro-extensions) | `main` | Kotlin source, Gradle build, CI |
| [rentaro-repo](https://github.com/rentaroarvin/rentaro-repo) | `repo` | Published index, APKs, icons |

CI builds signed APKs on every push to `main`, regenerates the index, and
pushes it to the index repo via a write-scoped deploy key.

## Sources

| Name | Lang | Metadata | Streams |
| --- | --- | --- | --- |
| Rentaro | en | TMDB v3 (keyless mirror) | Videasy |

### How it works

1. **Catalogue** — Browsing, search, filters, and episode lists come from a
   TMDB v3 mirror. It is API-compatible with `api.themoviedb.org/3`
   (`/trending`, `/discover`, `/search`, `/{type}/{id}`, `/tv/{id}/season/{n}`)
   but injects the API key server-side, so no key ships in the APK.
2. **Seed** — A short-lived token (~30 s TTL) is fetched per title.
3. **Sources** — Nine Videasy backends are queried in parallel with a
   double-percent-encoded title and `enc=2`, returning an encrypted payload.
4. **Decrypt** — The payload is decrypted, then HLS/DASH master playlists are
   expanded into individual quality variants with subtitles attached.

Results are cached briefly per title, and a circuit breaker skips backends that
fail repeatedly.

## Building

Requires a JDK (17 matches CI; 21 also works) and the Android SDK.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :src:en:rentaro:assembleDebug     # unsigned, for local testing
./gradlew assembleRelease                   # signed, needs the values below
```

Release signing reads `signingkey.jks` from the repo root plus the `ALIAS`,
`KEY_STORE_PASSWORD`, and `KEY_PASSWORD` environment variables. The keystore is
gitignored and stored in CI as the base64 secret `SIGNING_KEY`.

To regenerate the index locally after a release build:

```bash
python3 .github/scripts/create-repo.py   # writes repo/
```

## Layout

```
gradle/build-logic/   kei.plugins.* Gradle plugins
core/                 keiyoushi.utils helpers
lib/playlistutils/    HLS/DASH playlist expansion
common/               shared manifest + ProGuard rules
src/en/rentaro/       the extension
.github/              CI workflow and index generator
```

Bumping `extVersionCode` in `src/en/rentaro/build.gradle` is what signals an
update to clients.

> Renaming a source or changing its `lang` changes its generated ID and orphans
> existing library entries.

## Credits

Build infrastructure and the stream-resolution logic are derived from
[yuzono/anime-extensions](https://github.com/yuzono/anime-extensions)
(Apache-2.0). See [LICENSE](LICENSE).
