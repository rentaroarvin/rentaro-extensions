package eu.kanade.tachiyomi.animeextension.en.arvindf

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

/** DramaWave's language choices, `value` to display name, as its own selector lists them (19 on 2026-11-02). */
internal val DRAMAWAVE_LANGUAGES = listOf(
    "en-US" to "English",
    "id-ID" to "Indonesia",
    "zh-CN" to "Chinese (Simplified)",
    "zh-TW" to "Chinese (Traditional)",
    "ja-JP" to "Japanese",
    "ko-KR" to "Korean",
    "es-MX" to "Espanol",
    "pt-PT" to "Portuguese",
    "fr-FR" to "French",
    "ru-RU" to "Russian",
    "de-DE" to "German",
    "vi-VN" to "Vietnamese",
    "it-IT" to "Italian",
    "tr-TR" to "Turkish",
    "ms-MY" to "Malay",
    "th-TH" to "Thai",
    "tl-PH" to "Filipino",
    "hi-IN" to "Hindi",
    "ar-SA" to "Arabic",
)

/** Which catalogue to browse: the DramaWave subsite, or one of the provider portals. Each has its own titles. */
internal class CatalogueFilter :
    AnimeFilter.Select<String>(
        "Catalogue",
        arrayOf("DramaWave") + Portals.ALL.map { it.label }.toTypedArray(),
    ) {
    /** The chosen provider portal, or null for DramaWave. */
    val portal: Portal? get() = Portals.ALL.getOrNull(state - 1)
}

/** Only read for the DramaWave catalogue. It chooses the language of titles and subtitles. */
internal class LanguageFilter :
    AnimeFilter.Select<String>(
        "DramaWave language",
        DRAMAWAVE_LANGUAGES.map { it.second }.toTypedArray(),
    ) {
    val code: String get() = DRAMAWAVE_LANGUAGES.getOrNull(state)?.first ?: DRAMAWAVE_LANGUAGES.first().first
}
