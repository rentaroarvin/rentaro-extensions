package eu.kanade.tachiyomi.animeextension.en.arvinnd

internal data class ArvinNdProvider(val id: String, val label: String)

/**
 * The Narto Drama providers this extension lists.
 *
 * Narto Drama advertises 56 providers. A provider is listed when, on 2026-11-01, an episode resolved
 * through the order the extension uses (primary source, then the site-hosted `direct_play_url`, then the
 * page's `refresh-source`) and ffmpeg decoded it with exit code 0 and decoded frames, from a plain
 * `okhttp` User-Agent with no cookies or Referer. Most providers were checked on one or two titles and
 * two episodes each, so a title or episode can still fail for a listed provider.
 *
 * Needs a note when changing this list:
 * - Several providers serve HLS whose segments have no file extension (GoodShort, Velolo, SAROS TV,
 *   Vyntage, Vigloo, FlareFlow, SixthShort, MyRelle, DotDrama II, DramaWave, FreeReels, OhMyTV,
 *   DramaShorts, FlickReels, FunDrama, GoroGoro, HappyShort, KalosTV). ffmpeg decoded them only with
 *   `-extension_picky 0`; they have not been tried in the Aniyomi player.
 * - Reelife, FreeReels, Reelala and MicroDrama resolve to the player of another provider (DramaBox,
 *   DramaWave, FlickReels, DotDrama), so they are not independent health checks of their own backends.
 * - Playlet is partial: episode 1 played on both titles, but episode 17 of one title stayed 410 after a
 *   refresh and episode 12 of the other answered `stream_temporarily_unavailable`. MyRelle is partial
 *   too: episode 31 of one title answered `stream_not_available`, while the other title's played.
 * - KalosTV is contested. One refresh decoded episodes 1 and 30 here, but the audit in
 *   `narto-provider-audit-2026-11-01` saw the refresh report success and the media still answer 403.
 * - These could not be re-run in the final pass because narto-drama.com began answering HTTP 522 partway
 *   through it, so they rest on earlier runs and the audit: TopDrama, Storeel and ShortMax (the audit's
 *   local copy or refresh decoded), and StardustTV, StarShort, Velolo, Vigloo, Vyntage and SixthShort
 *   (decoded in earlier runs; SixthShort's last attempt failed just as the 522s started).
 *
 * Left out on purpose, with what was observed:
 * - JoyReels, CubeTV, DramaNova: HTTP 403 or 410 from the primary, the local copy and the refresh alike.
 * - FreeDrama: episode 1 plays, but episodes 236 and 258 stayed 410 after a refresh, and the audit saw the
 *   same for episode 10.
 * - ReelBuzz: plays, but its catalogue and titles are Indonesian.
 * - iDrama: not tested here. The audit reports that the site requires watching five ads before it
 *   releases the stream.
 */
internal val ARVIN_ND_PROVIDERS = listOf(
    ArvinNdProvider("anyreel", "AnyReel"),
    ArvinNdProvider("bibishort", "BibiShort"),
    ArvinNdProvider("candyjar", "CandyJar"),
    ArvinNdProvider("dotdrama", "DotDrama"),
    ArvinNdProvider("dotdrama2", "DotDrama II"),
    ArvinNdProvider("dramabite", "Dramabite"),
    ArvinNdProvider("dramabox", "DramaBox"),
    ArvinNdProvider("dramashine", "DramaShine"),
    ArvinNdProvider("dramashorts", "DramaShorts"),
    ArvinNdProvider("dramatv", "DramaTV"),
    ArvinNdProvider("dramawave", "DramaWave"),
    ArvinNdProvider("dreameshort", "DreameShort"),
    ArvinNdProvider("flareflow", "FlareFlow"),
    ArvinNdProvider("flextv", "FlexTV"),
    ArvinNdProvider("flickreels", "FlickReels"),
    ArvinNdProvider("freereels", "FreeReels"),
    ArvinNdProvider("fundrama", "Fun Drama"),
    ArvinNdProvider("goodshort", "GoodShort"),
    ArvinNdProvider("gorogoro", "GoroGoro"),
    ArvinNdProvider("happyshort", "HappyShort"),
    ArvinNdProvider("kalostv", "KalosTV"),
    ArvinNdProvider("melolo", "Melolo"),
    ArvinNdProvider("microdrama", "MicroDrama"),
    ArvinNdProvider("minishorts", "MiniShorts"),
    ArvinNdProvider("minutedrama", "MinuteDrama"),
    ArvinNdProvider("moboreels", "MoboReels"),
    ArvinNdProvider("mydrama", "My Drama"),
    ArvinNdProvider("myrelle", "MyRelle"),
    ArvinNdProvider("netshort", "NetShort"),
    ArvinNdProvider("ohmytv", "OhMyTV"),
    ArvinNdProvider("pinedrama", "PineDrama"),
    ArvinNdProvider("playlet", "Playlet"),
    ArvinNdProvider("rapidtv", "RapidTV"),
    ArvinNdProvider("rapidtv2", "RapidTV II"),
    ArvinNdProvider("raptdrama", "RaptDrama"),
    ArvinNdProvider("reelala", "Reelala"),
    ArvinNdProvider("reelife", "Reelife"),
    ArvinNdProvider("reelshort", "ReelShort"),
    ArvinNdProvider("sarostv", "SAROS TV"),
    ArvinNdProvider("serealplus", "Sereal+"),
    ArvinNdProvider("shortical", "Shortical"),
    ArvinNdProvider("shortmax", "ShortMax"),
    ArvinNdProvider("sixthshort", "SixthShort"),
    ArvinNdProvider("stardusttv", "StardustTV"),
    ArvinNdProvider("starshort", "StarShort"),
    ArvinNdProvider("storeel", "Storeel"),
    ArvinNdProvider("topdrama", "TopDrama"),
    ArvinNdProvider("velolo", "Velolo"),
    ArvinNdProvider("vigloo", "Vigloo"),
    ArvinNdProvider("vyntage", "Vyntage"),
)
