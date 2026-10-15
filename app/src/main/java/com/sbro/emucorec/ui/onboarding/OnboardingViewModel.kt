package com.sbro.emucorec.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jakewharton.processphoenix.ProcessPhoenix
import com.sbro.emucorec.core.CoreBinaryFingerprint
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.InstallStateBus
import com.sbro.emucorec.core.NativeLibraryLoader
import com.sbro.emucorec.core.Ps3StorageLocation
import com.sbro.emucorec.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnboardingUiState(
    val currentPage: Int = 0,
    val totalPages: Int = 4,
    val storagePath: String = "",
    val storageLocations: List<Ps3StorageLocation> = emptyList(),
    val storageChangeInProgress: Boolean = false,
    val storageErrorMessage: String? = null,
    val canContinue: Boolean = true
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences(application)

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            storagePath = EmulatorStorage.ps3Root(application).absolutePath,
            storageLocations = EmulatorStorage.availableStorageLocations(application),
            canContinue = true
        )
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun goNext() {
        _uiState.value = _uiState.value.copy(
            currentPage = (_uiState.value.currentPage + 1).coerceAtMost(_uiState.value.totalPages - 1)
        )
    }

    fun goBack() {
        _uiState.value = _uiState.value.copy(
            currentPage = (_uiState.value.currentPage - 1).coerceAtLeast(0)
        )
    }

    fun setCurrentPage(page: Int) {
        _uiState.value = _uiState.value.copy(
            currentPage = page.coerceIn(0, _uiState.value.totalPages - 1)
        )
    }

    suspend fun completeOnboarding() = withContext(Dispatchers.IO) {
        preferences.lastCoreBinaryFingerprint = CoreBinaryFingerprint.current(getApplication())
        preferences.onboardingCompleted = true
    }

    fun selectStorageLocation(rootPath: String) {
        val context = getApplication<Application>()
        if (EmulatorStorage.storageRoot(context).absolutePath == rootPath) return
        changeStorageLocation { appContext ->
            EmulatorStorage.selectStorageRoot(
                context = appContext,
                rootPath = rootPath,
                migrateExistingData = true
            )
        }
    }

    private fun changeStorageLocation(
        selectRoot: (Application) -> Unit
    ) {
        if (_uiState.value.storageChangeInProgress) return
        val context = getApplication<Application>()
        val restartRequired = NativeLibraryLoader.isNativeSessionInitialized()
        _uiState.value = _uiState.value.copy(storageChangeInProgress = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                selectRoot(context)
                _uiState.value = _uiState.value.copy(
                    storagePath = EmulatorStorage.ps3Root(context).absolutePath,
                    storageLocations = EmulatorStorage.availableStorageLocations(context),
                    storageChangeInProgress = false,
                    storageErrorMessage = null
                )
                InstallStateBus.notifyCompleted()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    storageChangeInProgress = false,
                    storageErrorMessage = it.message ?: "Storage change failed"
                )
            }.onSuccess {
                if (restartRequired) {
                    ProcessPhoenix.triggerRebirth(context.applicationContext)
                }
            }
        }
    }

    fun consumeStorageError() {
        _uiState.value = _uiState.value.copy(storageErrorMessage = null)
    }
}
