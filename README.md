<div align="center">

| Install on Aniyomi | Install on Anikku | Build |
|:------------------:|:-----------------:|:-----:|
| [![Install](https://img.shields.io/badge/Click%20here%20to%20install%20repo-gray?style=flat&labelColor=red)](aniyomi://add-repo?url=https://raw.githubusercontent.com/rentaroarvin/rentaro-repo/repo/index.min.json) | [![Install](https://img.shields.io/badge/Click%20here%20to%20install%20repo-gray?style=flat&labelColor=red)](anikku://add-repo?url=https://raw.githubusercontent.com/rentaroarvin/rentaro-repo/repo/index.min.json) | ![CI](https://github.com/rentaroarvin/rentaro-extensions/actions/workflows/build_push.yml/badge.svg) |

</div>

# Rentaro Extensions

This repository contains a personal extension catalogue for the
[Anikku](https://github.com/komikku-app/anikku) or
[Aniyomi](https://github.com/aniyomiorg/aniyomi) forks.

Rentaro supports browsing movies and TV shows, filters, quality preferences,
subtitle limits, and progressive stream discovery on compatible hosts.

## How to add the repo

* Tap one of the install buttons above, or
* Copy & paste the following URL into **Settings → Browse → Extension repos → Add**:

```html
https://raw.githubusercontent.com/rentaroarvin/rentaro-repo/repo/index.min.json
```

Then install **Rentaro** from **Browse → Extensions**. Enable **Show NSFW
sources** in settings if it does not appear in the list.

### Manual downloads

If you prefer to directly download the APK files, they are available in the
[`repo` branch](https://github.com/rentaroarvin/rentaro-repo/tree/repo/apk) of
the index repository.

## Sources

| Name | Language |
| --- | --- |
| Rentaro | en |

## Progressive stream resolution

Stream providers can differ widely in response time. Returning only one finished
list would withhold usable streams until every enabled provider had completed.

The extension therefore also implements `ProgressiveVideoSource`, which reports
the cumulative stream list as provider results are collected:

```kotlin
interface ProgressiveVideoSource : AnimeSource {
    fun getVideoListFlow(episode: SEpisode): Flow<List<Video>>
}
```

Every emission is cumulative and fully ordered, so a host can treat the latest as
the whole list without merging anything. Playback can begin before every enabled
provider has finished, and the picker fills in as more results are published.

This is **opt-in and detected with `is`**, exactly as `ConfigurableAnimeSource`
already is. A host that does not know the interface keeps calling
`getVideoList`, which returns the flow's terminal emission — the same list, in
the same order. Support currently exists in
[WatchBox](https://github.com/nicartjay/watchbox) 4.12.0 and later.

The interface is declared in `lib/hostapi` because `aniyomi-extensions-lib` does
not ship it. That module is consumed with `compileOnly`, so the class is
*referenced* by the extension and *defined* only by the host — the same
arrangement as the rest of the `eu.kanade.tachiyomi` ABI. Bundling a copy would
be worse than omitting it: the host's interface and a packaged duplicate are
different types to the classloader, the `is` check would never match, and the
extension would silently fall back to the blocking path. `assembleDebug` output
can be checked with `dexdump` to confirm nothing under
`eu/kanade/tachiyomi/animesource` is ever defined in the APK.

## Project layout

| Path | Purpose |
| --- | --- |
| `src/en/rentaro` | Rentaro source, filters, DTOs, stream extraction, and protocol implementations |
| `lib/playlistutils` | HLS and DASH playlist expansion shared by the extension |
| `lib/hostapi` | Compile-only declarations for host-owned optional interfaces |
| `core` | Shared networking, serialization, preferences, URL, and coroutine utilities |
| `gradle/build-logic` | Android extension and formatting convention plugins |
| `.github/workflows/build_push.yml` | Signed release build and repository publication |
| `.github/scripts/create-repo.py` | APK/icon collection and index generation |

## Building

Requires a JDK (17 matches CI; 21 also works) and the Android SDK.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :src:en:rentaro:assembleDebug     # unsigned, for local testing
./gradlew assembleRelease                   # signed
```

If `java` is installed but Gradle reports that no runtime is available, set
`JAVA_HOME` explicitly before invoking the wrapper. On Apple Silicon with
Homebrew OpenJDK 21, the path is commonly `/opt/homebrew/opt/openjdk@21`.

Release signing reads `signingkey.jks` from the repo root plus the `ALIAS`,
`KEY_STORE_PASSWORD`, and `KEY_PASSWORD` environment variables.

Useful checks:

```bash
./gradlew spotlessCheck
./gradlew :src:en:rentaro:assembleDebug
```

The debug APK is written under
`src/en/rentaro/build/outputs/apk/debug/`. Its `v14.<code>` suffix is derived
from the extension library major version and `extVersionCode`.

## Publishing

Pushing to `main` builds signed APKs and publishes the index automatically.
Bumping `extVersionCode` in `src/en/rentaro/build.gradle` is what signals an
update to clients. The workflow verifies each APK signature, regenerates
`index.json`, `index.min.json`, and `repo.json`, then pushes the artifacts to the
`repo` branch of the separate index repository.

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

Build infrastructure and source code are derived from
[yuzono/anime-extensions](https://github.com/yuzono/anime-extensions).

## Disclaimer

This project does not have any affiliation with the content providers available.

This project is not affiliated with Anikku/Aniyomi. Don't ask for help about
these extensions at the official support means of Anikku/Aniyomi. All credits to
the codebase goes to the original contributors.
