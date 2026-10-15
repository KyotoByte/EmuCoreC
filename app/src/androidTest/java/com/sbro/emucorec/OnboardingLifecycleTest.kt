package com.sbro.emucorec

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucorec.core.CoreBinaryFingerprint
import com.sbro.emucorec.core.CoreUpdateResetAction
import com.sbro.emucorec.core.decideCoreUpdateResetAction
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingLifecycleTest {
    @Test
    fun completingFreshOnboardingDoesNotRequestACoreReset() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        val preferences = AppPreferences(application)
        val previousCompleted = preferences.onboardingCompleted
        val previousFingerprint = preferences.lastCoreBinaryFingerprint
        try {
            preferences.onboardingCompleted = false
            preferences.lastCoreBinaryFingerprint = null
            val fingerprint = CoreBinaryFingerprint.current(application)
            assertNotNull("The installed APK must contain the native core", fingerprint)

            OnboardingViewModel(application).completeOnboarding()

            assertTrue(preferences.onboardingCompleted)
            assertEquals(fingerprint, preferences.lastCoreBinaryFingerprint)
            assertEquals(CoreUpdateResetAction.NONE, decideCoreUpdateResetAction(
                storedFingerprint = preferences.lastCoreBinaryFingerprint,
                currentFingerprint = fingerprint,
                hadExistingInstall = preferences.onboardingCompleted,
            ))
        } finally {
            preferences.lastCoreBinaryFingerprint = previousFingerprint
            preferences.onboardingCompleted = previousCompleted
        }
    }
}
