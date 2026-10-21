package com.sbro.emucorec.ui.gamemanager

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucorec.core.InstallStateBus
import com.sbro.emucorec.core.GpuDriverManager
import com.sbro.emucorec.core.InstalledGpuDriver
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.core.Ps3CoreConfigRepository
import com.sbro.emucorec.core.Ps3CoreSettingOverrides
import com.sbro.emucorec.core.Ps3GameSettingsRepository
import com.sbro.emucorec.data.InstalledGameRepository
import com.sbro.emucorec.data.InstalledPs3Game
import com.sbro.emucorec.data.KeyboardMouseSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class GameManagerUiState(
    val games: List<InstalledPs3Game> = emptyList(),
    val selectedTitleId: String? = null,
    val config: Ps3CoreConfig = Ps3CoreConfig(),
    val defaults: Ps3CoreConfig = Ps3CoreConfig(),
    val installedGpuDrivers: List<InstalledGpuDriver> = emptyList(),
    val customDriverOverride: String? = null,
    val keyboardMouse: KeyboardMouseSettings? = null,
    val hasCustomProfile: Boolean = false,
    val isLoading: Boolean = true,
    val hasLoadedOnce: Boolean = false
) {
    val selectedGame: InstalledPs3Game?
        get() = games.firstOrNull { it.titleId == selectedTitleId }
}

class GameManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val gameRepository = InstalledGameRepository()
    private val globalRepository = Ps3CoreConfigRepository(application)
    private val perGameRepository = Ps3GameSettingsRepository(application)
    private val gpuDriverManager = GpuDriverManager(application)
    private val profileSaveQueue = Channel<ProfileWriteRequest>(Channel.UNLIMITED)
    private val latestProfileWrites = linkedMapOf<String, ProfileWriteRequest>()
    private val profileSaveJob: Job

    private val _uiState = MutableStateFlow(GameManagerUiState())
    val uiState: StateFlow<GameManagerUiState> = _uiState.asStateFlow()

    init {
        profileSaveJob = viewModelScope.launch(Dispatchers.IO) {
            for (request in profileSaveQueue) {
                runCatching {
                    when (request) {
                        is ProfileWriteRequest.Save -> perGameRepository.saveProfile(
                            request.titleId,
                            request.config,
                            request.customDriverOverride,
                            request.keyboardMouse
                        )
                        is ProfileWriteRequest.Reset -> perGameRepository.reset(request.titleId)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Could not persist settings for ${request.titleId}", error)
                }
            }
        }
        refresh()
        viewModelScope.launch {
            InstallStateBus.events.collect {
                // Skip the replayed event that arrives right after creation:
                // the refresh above already covers that state.
                if (_uiState.value.hasLoadedOnce) {
                    refresh(_uiState.value.selectedTitleId)
                }
            }
        }
    }

    fun refresh(preferredTitleId: String? = _uiState.value.selectedTitleId) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val games = runCatching { gameRepository.loadInstalledGames(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Game library scan failed", error)
                    emptyList()
                }
            val installedGpuDrivers = runCatching { gpuDriverManager.listInstalledDrivers() }
                .getOrElse { error ->
                    Log.e(TAG, "GPU driver list failed", error)
                    emptyList()
                }
            val selected = preferredTitleId
                ?.takeIf { id -> games.any { it.titleId == id } }
                ?: games.firstOrNull()?.titleId
            val defaults = globalRepository.ensureDefaultsPersisted()
            val profile = selected?.let(perGameRepository::loadProfile)
            _uiState.value = GameManagerUiState(
                games = games,
                selectedTitleId = selected,
                config = profile?.config ?: defaults,
                defaults = defaults,
                installedGpuDrivers = installedGpuDrivers,
                customDriverOverride = profile?.customDriverOverride,
                keyboardMouse = profile?.keyboardMouse,
                hasCustomProfile = selected?.let { titleId ->
                    perGameRepository.hasCustomConfig(titleId) ||
                        Ps3CoreSettingOverrides.gameOverrideCount(context, titleId) > 0
                } == true,
                isLoading = false,
                hasLoadedOnce = true
            )
        }
    }

    fun selectGame(titleId: String) {
        if (_uiState.value.selectedTitleId == titleId) return
        refresh(titleId)
    }

    fun updateSelected(transform: (Ps3CoreConfig) -> Ps3CoreConfig) {
        val titleId = _uiState.value.selectedTitleId ?: return
        val updated = transform(_uiState.value.config)
        val driverOverride = _uiState.value.customDriverOverride
        val keyboardMouse = _uiState.value.keyboardMouse
        _uiState.value = _uiState.value.copy(
            config = updated,
            hasCustomProfile = true
        )
        enqueueProfileWrite(ProfileWriteRequest.Save(titleId, updated, driverOverride, keyboardMouse))
    }

    fun updateKeyboardMouse(settings: KeyboardMouseSettings?) {
        val titleId = _uiState.value.selectedTitleId ?: return
        val state = _uiState.value
        val hasCoreOverride = state.config != state.defaults ||
            state.customDriverOverride != null ||
            Ps3CoreSettingOverrides.gameOverrideCount(getApplication(), titleId) > 0
        _uiState.value = state.copy(
            keyboardMouse = settings,
            hasCustomProfile = settings != null || hasCoreOverride
        )
        enqueueProfileWrite(
            ProfileWriteRequest.Save(
                titleId,
                state.config,
                state.customDriverOverride,
                settings
            )
        )
    }

    fun selectCustomDriverOverride(driverName: String?) {
        val titleId = _uiState.value.selectedTitleId ?: return
        val defaults = _uiState.value.defaults
        val updated = _uiState.value.config.copy(customDriverName = driverName ?: defaults.customDriverName)
        _uiState.value = _uiState.value.copy(
            config = updated,
            customDriverOverride = driverName,
            hasCustomProfile = true
        )
        enqueueProfileWrite(
            ProfileWriteRequest.Save(
                titleId,
                updated,
                driverName,
                _uiState.value.keyboardMouse
            )
        )
    }

    fun refreshCustomProfileFlag() {
        val titleId = _uiState.value.selectedTitleId ?: return
        val context = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            hasCustomProfile = perGameRepository.hasCustomConfig(titleId) ||
                Ps3CoreSettingOverrides.gameOverrideCount(context, titleId) > 0,
        )
    }

    fun resetSelectedToGlobal() {
        val titleId = _uiState.value.selectedTitleId ?: return
        val defaults = _uiState.value.defaults
        _uiState.value = _uiState.value.copy(
            config = defaults,
            customDriverOverride = null,
            keyboardMouse = null,
            hasCustomProfile = false
        )
        Ps3CoreSettingOverrides.clearGame(getApplication(), titleId)
        enqueueProfileWrite(ProfileWriteRequest.Reset(titleId))
    }

    override fun onCleared() {
        profileSaveQueue.cancel()
        runBlocking { profileSaveJob.cancelAndJoin() }
        latestProfileWrites.values.forEach { request ->
            runCatching {
                when (request) {
                    is ProfileWriteRequest.Save -> perGameRepository.saveProfile(
                        request.titleId,
                        request.config,
                        request.customDriverOverride,
                        request.keyboardMouse
                    )
                    is ProfileWriteRequest.Reset -> perGameRepository.reset(request.titleId)
                }
            }.onFailure { error ->
                Log.e(TAG, "Could not flush settings for ${request.titleId}", error)
            }
        }
    }

    private fun enqueueProfileWrite(request: ProfileWriteRequest) {
        latestProfileWrites[request.titleId] = request
        profileSaveQueue.trySend(request)
    }

    private sealed interface ProfileWriteRequest {
        val titleId: String

        data class Save(
            override val titleId: String,
            val config: Ps3CoreConfig,
            val customDriverOverride: String?,
            val keyboardMouse: KeyboardMouseSettings?
        ) : ProfileWriteRequest

        data class Reset(override val titleId: String) : ProfileWriteRequest
    }

    private companion object {
        const val TAG = "GameManagerViewModel"
    }
}
