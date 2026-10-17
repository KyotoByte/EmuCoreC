package com.sbro.emucorec.ui.common

/**
 * Aspect ratio of the cover artwork shown across the app.
 *
 * Covers are fetched as IGDB's `t_cover_big_2x` tier, which renders at 528x748
 * and keeps the original 264:374 ratio, so this single ratio holds everywhere.
 * Containers that match it exactly never crop the art -- a fixed 3:4 frame did,
 * which is why some tiles showed letterbox lines and why GTA San Andreas' dark
 * key art made the mismatch so obvious.
 */
const val COVER_ART_ASPECT_RATIO = 264f / 374f

/**
 * One canonical cover tier for the whole app: every stored cover URL (the
 * catalog only stores `t_cover_big`) is rewritten to 528x748 `t_cover_big_2x`,
 * which is sharp on tablet grids and the detail hero, small enough to download
 * and decode quickly, and -- crucially -- the exact same string everywhere.
 *
 * The previous "maximum quality" approach stripped the `/t_<size>/` transform
 * to fetch the upload's original. IGDB does not serve transform-less originals
 * (they 404), so every cover first issued a failing request -- slow when online,
 * instant offline -- and the memory cache was keyed by that 404 URL, which made
 * every mode switch re-run the same doomed fetch. A single canonical URL is
 * used as the memory key, the disk key and the fetch target, so a cover that is
 * loaded once stays loaded.
 *
 * Non-IGDB URLs (avatars, profile photos, ...) and IGDB non-cover transforms
 * (hero screenshots at `t_1080p`) are returned unchanged.
 */
fun igdbCoverUrl(url: String): String {
    if (!url.contains("images.igdb.com", ignoreCase = true)) return url
    if (!url.contains("/image/upload/", ignoreCase = true)) return url
    if (!url.contains("/t_cover_", ignoreCase = true)) return url
    return url.replace(
        Regex("/image/upload/t_cover_[^/]+/", RegexOption.IGNORE_CASE),
        "/image/upload/t_cover_big_2x/"
    )
}
