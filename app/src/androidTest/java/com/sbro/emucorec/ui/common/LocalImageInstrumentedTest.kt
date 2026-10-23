package com.sbro.emucorec.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalImageInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replacingLoadedPathWithMissingCoverRemovesPreviousArtwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("local-image", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val path = mutableStateOf<String?>(file.absolutePath)
        try {
            compose.setContent {
                MaterialTheme {
                    LocalImage(path.value, "Game artwork", "Game", Modifier.size(80.dp))
                }
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription("Game artwork").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { path.value = null }
            compose.onNodeWithText("G").assertIsDisplayed()
            compose.onNodeWithContentDescription("Game artwork").assertDoesNotExist()
        } finally {
            file.delete()
        }
    }
}
