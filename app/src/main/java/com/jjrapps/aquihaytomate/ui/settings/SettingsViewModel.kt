
package com.jjrapps.aquihaytomate.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjrapps.aquihaytomate.BuildConfig
import com.jjrapps.aquihaytomate.data.backup.BackupManager
import com.jjrapps.aquihaytomate.domain.model.AlertSound
import com.jjrapps.aquihaytomate.domain.model.AppLanguage
import com.jjrapps.aquihaytomate.domain.model.KeepScreenOnMode
import com.jjrapps.aquihaytomate.domain.model.SlotType
import com.jjrapps.aquihaytomate.domain.model.WidgetBackground
import com.jjrapps.aquihaytomate.domain.repository.AlertPlayer
import com.jjrapps.aquihaytomate.domain.repository.SettingsRepository
import com.jjrapps.aquihaytomate.domain.repository.TimerAlarmScheduler
import com.jjrapps.aquihaytomate.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: TimerAlarmScheduler,
    private val alertPlayer: AlertPlayer,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val openSheet = MutableStateFlow<SettingsSheet?>(null)
    private val permissions = MutableStateFlow(
        PermissionState(notificationsGranted = true, exactAlarmsGranted = true),
    )

    val uiState: StateFlow<SettingsUiState> =
        combine(observeSettings(), openSheet, permissions) { settings, sheet, permissionState ->
            SettingsUiState.Success(
                settings = settings,
                notificationsGranted = permissionState.notificationsGranted,
                exactAlarmsGranted = permissionState.exactAlarmsGranted,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                openSheet = sheet,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SettingsUiState.Loading,
        )

    fun onResume(notificationsGranted: Boolean) {
        permissions.value = PermissionState(
            notificationsGranted = notificationsGranted,
            exactAlarmsGranted = alarmScheduler.canScheduleExactAlarms(),
        )
    }

    fun onSheetRequested(sheet: SettingsSheet) {
        openSheet.value = sheet
    }

    fun onDurationSelected(slotType: SlotType, minutes: Int) = update {
        settingsRepository.setDurationMinutes(slotType, minutes)
    }

    fun onPomodorosPerCycleSelected(count: Int) = update {
        settingsRepository.setPomodorosPerCycle(count)
    }

    fun onDailyGoalSelected(pomodoros: Int) = update {
        settingsRepository.setDailyGoal(pomodoros)
    }

    fun onFocusAlertSoundSelected(sound: AlertSound) = update {
        settingsRepository.setFocusAlertSound(sound)
        playPreview(sound)
    }

    fun onBreakAlertSoundSelected(sound: AlertSound) = update {
        settingsRepository.setBreakAlertSound(sound)
        playPreview(sound)
    }

    fun onFocusAlertRepeatsSelected(count: Int) = update {
        settingsRepository.setFocusAlertRepeats(count)
        val settings = settingsRepository.current()
        playPreview(settings.focusAlertSound, repeats = count)
    }

    fun onBreakAlertRepeatsSelected(count: Int) = update {
        settingsRepository.setBreakAlertRepeats(count)
        val settings = settingsRepository.current()
        playPreview(settings.breakAlertSound, repeats = count)
    }

    private suspend fun playPreview(sound: AlertSound, repeats: Int = 1) {
        alertPlayer.stop()
        if (sound.isAudible) alertPlayer.play(sound, vibrationSeconds = 0, repeats = repeats)
    }

    fun onSheetDismissed() {
        alertPlayer.stop()
        openSheet.value = null
    }

    fun onVibrationSecondsSelected(seconds: Int) = update {
        settingsRepository.setVibrationSeconds(seconds)
    }

    fun onWidgetBackgroundSelected(background: WidgetBackground) = update {
        settingsRepository.setWidgetBackground(background)
    }

    fun onLanguageSelected(language: AppLanguage) = update {
        settingsRepository.setLanguage(language)
    }

    fun onAutoStartBreakChanged(enabled: Boolean) = update {
        settingsRepository.setAutoStartBreak(enabled)
    }

    fun onAutoStartFocusChanged(enabled: Boolean) = update {
        settingsRepository.setAutoStartFocus(enabled)
    }

    fun onKeepScreenOnSelected(mode: KeepScreenOnMode) = update {
        settingsRepository.setKeepScreenOn(mode)
    }

    fun onLiquidAnimationChanged(enabled: Boolean) = update {
        settingsRepository.setLiquidAnimationEnabled(enabled)
    }

    // Backup export

    fun exportBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                backupManager.exportTo(uri)
                "Backup exported."
            }.getOrElse {
                "Export failed: ${it.message ?: "unknown error"}"
            }
            onResult(message)
        }
    }

    // Backup import

    fun importBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                val result = backupManager.importFrom(uri)
                "Imported ${result.inserted} sessions; skipped ${result.skipped} duplicates. Settings restored."
            }.getOrElse {
                "Import failed: ${it.message ?: "unknown error"}"
            }
            onResult(message)
        }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private data class PermissionState(
        val notificationsGranted: Boolean,
        val exactAlarmsGranted: Boolean,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
