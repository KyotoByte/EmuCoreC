package com.sbro.emucorec.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverArtTest {
    @Test
    fun storedCoverTierIsRewrittenToTheCanonicalTier() {
        assertEquals(
            "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co22nc.jpg",
            igdbCoverUrl("https://images.igdb.com/igdb/image/upload/t_cover_big/co22nc.jpg")
        )
    }

    @Test
    fun canonicalTierIsStableAcrossRepeatedRewrites() {
        val canonical = "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co22nc.jpg"
        assertEquals(canonical, igdbCoverUrl(canonical))
        assertEquals(canonical, igdbCoverUrl(igdbCoverUrl(canonical)))
    }

    @Test
    fun anyCoverTierMapsToTheCanonicalTier() {
        // Only the tier segment is rewritten; the file name survives as-is.
        assertEquals(
            "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co22nc.png",
            igdbCoverUrl("https://images.igdb.com/igdb/image/upload/t_cover_small_2x/co22nc.png")
        )
    }

    @Test
    fun heroScreenshotsAreLeftAlone() {
        val hero = "https://images.igdb.com/igdb/image/upload/t_1080p/ar4ppq.jpg"
        assertEquals(hero, igdbCoverUrl(hero))
    }

    @Test
    fun nonIgdbUrlsAreLeftAlone() {
        val avatar = "https://firebasestorage.googleapis.com/v0/b/bucket/o/avatar.jpg?alt=media"
        assertEquals(avatar, igdbCoverUrl(avatar))
        assertEquals(
            "https://images.igdb.com/igdb/image/upload/co22nc.jpg",
            igdbCoverUrl("https://images.igdb.com/igdb/image/upload/co22nc.jpg")
        )
    }
}
