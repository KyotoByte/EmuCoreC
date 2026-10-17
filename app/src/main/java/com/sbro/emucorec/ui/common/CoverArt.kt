package com.sbro.emucorec.ui.common

/**
 * Aspect ratio of the cover artwork shown across the app.
 *
 * Covers come from IGDB's `t_cover_big` tier, which renders at 264x374, and the
 * detail hero is the same cover re-served at `t_1080p`, so this single ratio
 * holds everywhere. Containers that match it exactly never crop the art -- a
 * fixed 3:4 frame did, which is why some tiles showed letterbox lines and why
 * GTA San Andreas' dark key art made the mismatch so obvious.
 */
const val COVER_ART_ASPECT_RATIO = 264f / 374f

/**
 * Turns a stored IGDB image URL into the maximum-quality one: the original
 * upload, with the `/t_<size>/` transform segment removed. Non-IGDB URLs are
 * returned unchanged.
 */
fun igdbMaximumQualityUrl(url: String): String {
    if (!url.contains("/image/upload/", ignoreCase = true)) return url
    return url.replace(Regex("/t_[^/]+/"), "/")
}

/**
 * Degradation ladder for an IGDB image, tried in order when the maximum-quality
 * original cannot be fetched. The order is strictly LARGE to SMALL: for a cover
 * original, t_1080p renders ~763x1080, t_cover_big_2x 528x748 and t_cover_big
 * 264x374. Every entry is a server-side transform of the same upload, so a
 * cover that exists in any tier can still be shown.
 */
fun igdbImageFallbackUrls(url: String): List<String> {
    if (!url.contains("/image/upload/", ignoreCase = true)) return emptyList()
    val original = igdbMaximumQualityUrl(url)
    return listOf(
        original.replaceFirst("/image/upload/", "/image/upload/t_1080p/"),
        original.replaceFirst("/image/upload/", "/image/upload/t_cover_big_2x/"),
        original.replaceFirst("/image/upload/", "/image/upload/t_cover_big/"),
    ).distinct()
}
