package com.sbro.emucorec.ui.savestates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucorec.core.Ps3LaunchBridge
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.core.SaveStateOutcome
import com.sbro.emucorec.core.SaveStateRepository
import com.sbro.emucorec.core.SaveStateSlot
import com.sbro.emucorec.data.InstalledGameRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.EmulatorState
import net.rpcsx.RPCSX

data class SaveStatesGame(val titleId: String, val title: String, val iconPath: String?, val slots: List<SaveStateSlot>)
data class SaveStatesUiState(val games: List<SaveStatesGame> = emptyList(), val isLoading: Boolean = true, val busyKey: String? = null, val refreshFailed: Boolean = false)

class SaveStatesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SaveStateRepository(application)
    private val mutableState = MutableStateFlow(SaveStatesUiState())
    val uiState = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var focus: String? = null
    private var refreshGeneration = 0

    fun refresh(focusTitleId: String? = null) {
        focus = focusTitleId
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(isLoading = true, refreshFailed = false) }
            try {
                val context = getApplication<Application>()
                val games = InstalledGameRepository().loadInstalledGames(context).associateBy { it.titleId }
                val titles = focusTitleId?.takeIf(String::isNotBlank)?.let(::listOf) ?: repository.gamesWithSlots()
                val saved = titles.mapNotNull { titleId ->
                    val slots = repository.listSlots(titleId)
                    if (slots.isEmpty()) null else SaveStatesGame(titleId,
                        games[titleId]?.title?.takeIf(String::isNotBlank) ?: titleId,
                        games[titleId]?.iconPath, slots)
                }
                if (generation == refreshGeneration) mutableState.update { it.copy(games = saved) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == refreshGeneration) mutableState.update { it.copy(refreshFailed = true) }
            }
            finally {
                if (generation == refreshGeneration) mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun launchWithState(titleId: String, slot: Int, onComplete: (Ps3LaunchBridge.LaunchResult) -> Unit) {
        if (mutableState.value.busyKey != null) return
        mutableState.update { it.copy(busyKey = "$titleId:$slot") }
        viewModelScope.launch {
            try { onComplete(Ps3LaunchBridge.launchSaveState(getApplication(), titleId, slot)) }
            finally { mutableState.update { it.copy(busyKey = null) } }
        }
    }

    fun delete(titleId: String, slot: Int, onComplete: (SaveStateOutcome) -> Unit) {
        if (mutableState.value.busyKey != null) return
        mutableState.update { it.copy(busyKey = "$titleId:$slot") }
        viewModelScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    if (RPCSX.initialized && (RPCSX.instance.saveStateBusy() || Ps3Runtime.state() != EmulatorState.Stopped)) false
                    else repository.delete(titleId, slot)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { mutableState.update { it.copy(busyKey = null) } }
            onComplete(if (success) SaveStateOutcome.Success else SaveStateOutcome.Failed)
            if (success) refresh(focus)
        }
    }
}
