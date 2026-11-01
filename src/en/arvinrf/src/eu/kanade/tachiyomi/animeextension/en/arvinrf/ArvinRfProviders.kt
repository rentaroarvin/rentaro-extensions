package eu.kanade.tachiyomi.animeextension.en.arvinrf

internal data class ArvinRfProvider(val id: String, val label: String)

/**
 * The ReelFren providers this extension lists.
 *
 * ReelFren advertises 48 providers. A provider is listed when its first-episode stream decoded with
 * ffmpeg (exit code 0 and decoded frames, not merely quiet stderr) on 2026-11-01, using a plain
 * `okhttp` User-Agent and no Referer. Availability moves: JoyReels, RapidTV, ShortMax, MomeShort,
 * LoveShots and CubeTV answered 403 from their own CDNs on some titles and played on others, and
 * the API itself returns 502 for single titles now and then.
 *
 * Needs a note when changing this list:
 * - `wetv`: its plain home feed answers 502; only `category=10240` (the anime feed) works.
 * - `aniboy`, `vigloo`: HLS whose segments have no media file extension. ffmpeg decodes them only
 *   with `-extension_picky 0`; the site's own player plays AniBoy. Not yet tried in the Aniyomi player.
 * - `dramawave`, `freereels`, `candyjar`: fragmented-MP4 HLS. ffmpeg prints "Invalid NAL unit size"
 *   warnings on the master playlist but still decodes hundreds of frames, so the warning alone is not
 *   a reason to drop them. FreeReels' resolver also times out at times (502 after 18 s).
 * - `melolo`: only `server=1` is used. Depending on the title it returns either an unkeyed stream,
 *   which plays, or qualities carrying a `kid`, which the site decrypts client-side and which are
 *   skipped here. `server=2` needs the site's key lookup, which answered 502.
 *
 * Left out on purpose, with what was observed:
 * - `playlet`: AES-128 HLS. One of three titles decoded; the site's own player failed in WebCrypto
 *   decryption on the one it was tried with.
 * - `flareflow`: the full download of a segment came back as binary run through a lossy UTF-8
 *   conversion (replacement characters), so it cannot decode. A ranged request is intact, which the
 *   extension cannot rely on. Two of three titles still decoded in a later ffmpeg run.
 * - `bonustv`: the API returns a URL but its media proxy answers 403.
 * - `raptdrama`, `iqiyi`: the home feed answered 502. `dramanova`: the home feed is empty.
 */
internal val ARVIN_RF_PROVIDERS = listOf(
    ArvinRfProvider("mydrama", "MyDrama"),
    ArvinRfProvider("anamana", "Anamana"),
    ArvinRfProvider("anyreel", "AnyReel"),
    ArvinRfProvider("blinkdrama", "BlinkDrama"),
    ArvinRfProvider("cubetv", "CubeTV"),
    ArvinRfProvider("dramabox", "DramaBox"),
    ArvinRfProvider("flextv", "FlexTV"),
    ArvinRfProvider("flextv2", "FlexTV 2"),
    ArvinRfProvider("happyshort", "HappyShort"),
    ArvinRfProvider("joyreels", "JoyReels"),
    ArvinRfProvider("kalostv", "KalosTV"),
    ArvinRfProvider("loveshots", "LoveShots"),
    ArvinRfProvider("minishort", "MiniShort"),
    ArvinRfProvider("moboreels", "MoboReels"),
    ArvinRfProvider("momeshort", "MomeShort"),
    ArvinRfProvider("moreshort", "MoreShort"),
    ArvinRfProvider("netshort", "NetShort"),
    ArvinRfProvider("pinedrama", "PineDrama"),
    ArvinRfProvider("rapidtv", "RapidTV"),
    ArvinRfProvider("rapidtv2", "RapidTV 2"),
    ArvinRfProvider("reelife", "Reelife"),
    ArvinRfProvider("reelshort", "ReelShort"),
    ArvinRfProvider("sereal", "Sereal+"),
    ArvinRfProvider("shortical", "Shortical"),
    ArvinRfProvider("shortmax", "ShortMax"),
    ArvinRfProvider("sixthshort", "SixthShort"),
    ArvinRfProvider("soreel", "SoReel"),
    ArvinRfProvider("storeel", "StoReel"),
    ArvinRfProvider("storygo", "StoryGo"),
    ArvinRfProvider("storyreel", "StoryReel"),
    ArvinRfProvider("toonshort", "ToonShort"),
    ArvinRfProvider("vibeshort", "VibeShort"),
    ArvinRfProvider("winknovel", "WinkNovel"),
    ArvinRfProvider("aniboy", "AniBoy"),
    ArvinRfProvider("candyjar", "CandyJar"),
    ArvinRfProvider("dramawave", "DramaWave"),
    ArvinRfProvider("freereels", "FreeReels"),
    ArvinRfProvider("melolo", "Melolo"),
    ArvinRfProvider("movieboxshorts", "MovieBox Shorts"),
    ArvinRfProvider("shorten", "Shorten"),
    ArvinRfProvider("vigloo", "Vigloo"),
    ArvinRfProvider("wetv", "WeTV"),
)
