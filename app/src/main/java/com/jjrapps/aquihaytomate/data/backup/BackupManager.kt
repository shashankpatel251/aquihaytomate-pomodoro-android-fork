package com.jjrapps.aquihaytomate.data.backup

import android.content.Context
import android.net.Uri
import com.jjrapps.aquihaytomate.data.local.datastore.SettingsDataSource
import com.jjrapps.aquihaytomate.data.local.db.FocusSessionDao
import com.jjrapps.aquihaytomate.data.local.db.FocusSessionEntity
import com.jjrapps.aquihaytomate.domain.model.AlertSound
import com.jjrapps.aquihaytomate.domain.model.AppLanguage
import com.jjrapps.aquihaytomate.domain.model.KeepScreenOnMode
import com.jjrapps.aquihaytomate.domain.model.SlotType
import com.jjrapps.aquihaytomate.domain.model.TimerSettings
import com.jjrapps.aquihaytomate.domain.model.WidgetBackground
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable backup of user settings and completed focus history.
 *
 * The active timer state is intentionally excluded: importing a backup must never start,
 * pause, reset, or otherwise alter a running timer.
 */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessions: FocusSessionDao,
    private val settings: SettingsDataSource,
) {
    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAtEpochMs", System.currentTimeMillis())
            .put("settings", settingsToJson(settings.current()))
            .put("sessions", JSONArray().apply {
                sessions.allSessions().forEach { put(sessionToJson(it)) }
            })

        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(root.toString().toByteArray(Charsets.UTF_8))
        } ?: error("Could not open the selected backup file for writing.")
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "Backup file is too large." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("Could not open the selected backup file.")

        // Parse and validate the entire file before changing either database or settings.
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == FORMAT) { "This is not an Aquí Hay Tomate backup." }
        require(root.getInt("version") == VERSION) { "This backup version is not supported by this app." }

        val restoredSettings = settingsFromJson(root.getJSONObject("settings"))
        val array = root.getJSONArray("sessions")
        require(array.length() <= MAX_SESSIONS) { "Backup contains too many focus sessions." }
        val imported = ArrayList<FocusSessionEntity>(array.length())
        for (i in 0 until array.length()) {
            imported += sessionFromJson(array.getJSONObject(i))
        }

        // Room's UNIQUE(session_id, slot_index) plus INSERT IGNORE makes repeat imports safe.
        val insertResults = sessions.insertAll(imported)
        val inserted = insertResults.count { it != -1L }
        settings.restore(restoredSettings)
        ImportResult(inserted = inserted, skipped = imported.size - inserted)
    }

    private fun settingsToJson(s: TimerSettings) = JSONObject()
        .put("focusMinutes", s.focusMinutes)
        .put("shortBreakMinutes", s.shortBreakMinutes)
        .put("longBreakMinutes", s.longBreakMinutes)
        .put("pomodorosPerCycle", s.pomodorosPerCycle)
        .put("autoStartBreak", s.autoStartBreak)
        .put("autoStartFocus", s.autoStartFocus)
        .put("focusAlertSound", s.focusAlertSound.id)
        .put("breakAlertSound", s.breakAlertSound.id)
        .put("focusAlertRepeats", s.focusAlertRepeats)
        .put("breakAlertRepeats", s.breakAlertRepeats)
        .put("vibrationSeconds", s.vibrationSeconds)
        .put("keepScreenOn", s.keepScreenOn.id)
        .put("dailyGoal", s.dailyGoal)
        .put("widgetBackground", s.widgetBackground.id)
        .put("language", s.language.id)
        .put("liquidAnimationEnabled", s.liquidAnimationEnabled)
        .put("onboardingDone", s.onboardingDone)

    private fun settingsFromJson(j: JSONObject): TimerSettings {
        fun bounded(name: String, range: IntRange): Int =
            j.getInt(name).also { require(it in range) { "Invalid setting: $name" } }

        fun <T> enumId(name: String, values: List<T>, id: (T) -> String): T {
            val value = j.getString(name)
            return values.firstOrNull { id(it) == value }
                ?: error("Unknown value for setting: $name")
        }

        return TimerSettings(
            focusMinutes = bounded("focusMinutes", TimerSettings.FOCUS_MINUTES_RANGE),
            shortBreakMinutes = bounded("shortBreakMinutes", TimerSettings.SHORT_BREAK_MINUTES_RANGE),
            longBreakMinutes = bounded("longBreakMinutes", TimerSettings.LONG_BREAK_MINUTES_RANGE),
            pomodorosPerCycle = bounded("pomodorosPerCycle", TimerSettings.POMODOROS_PER_CYCLE_RANGE),
            autoStartBreak = j.getBoolean("autoStartBreak"),
            autoStartFocus = j.getBoolean("autoStartFocus"),
            focusAlertSound = enumId("focusAlertSound", AlertSound.entries, AlertSound::id),
            breakAlertSound = enumId("breakAlertSound", AlertSound.entries, AlertSound::id),
            focusAlertRepeats = bounded("focusAlertRepeats", TimerSettings.ALERT_REPEATS_RANGE),
            breakAlertRepeats = bounded("breakAlertRepeats", TimerSettings.ALERT_REPEATS_RANGE),
            vibrationSeconds = bounded("vibrationSeconds", TimerSettings.VIBRATION_SECONDS_RANGE),
            keepScreenOn = enumId("keepScreenOn", KeepScreenOnMode.entries, KeepScreenOnMode::id),
            dailyGoal = bounded("dailyGoal", TimerSettings.DAILY_GOAL_RANGE),
            widgetBackground = enumId("widgetBackground", WidgetBackground.entries, WidgetBackground::id),
            language = enumId("language", AppLanguage.entries, AppLanguage::id),
            liquidAnimationEnabled = j.getBoolean("liquidAnimationEnabled"),
            onboardingDone = j.getBoolean("onboardingDone"),
        )
    }

    private fun sessionToJson(s: FocusSessionEntity) = JSONObject()
        .put("sessionId", s.sessionId)
        .put("slotIndex", s.slotIndex)
        .put("slotType", s.slotType)
        .put("startedAtEpochMs", s.startedAtEpochMs)
        .put("endedAtEpochMs", s.endedAtEpochMs)
        .put("plannedDurationMs", s.plannedDurationMs)
        .put("actualFocusMs", s.actualFocusMs)
        .put("completed", s.completed)
        .put("timezoneId", s.timezoneId)
        .put("localDate", s.localDate.toString())

    private fun sessionFromJson(j: JSONObject): FocusSessionEntity {
        val slotType = j.getString("slotType")
        require(SlotType.entries.any { it.name == slotType }) { "Unknown focus-session type." }
        val zone = j.getString("timezoneId")
        ZoneId.of(zone) // Validate the time-zone ID before importing.
        val started = j.getLong("startedAtEpochMs")
        val ended = j.getLong("endedAtEpochMs")
        val planned = j.getLong("plannedDurationMs")
        val actual = j.getLong("actualFocusMs")
        val slotIndex = j.getInt("slotIndex")
        require(started >= 0 && ended >= started && planned >= 0 && actual >= 0 && slotIndex >= 0) {
            "Backup contains an invalid focus session."
        }
        return FocusSessionEntity(
            id = 0L, // Let this installation assign its own primary keys.
            sessionId = j.getLong("sessionId"),
            slotIndex = slotIndex,
            slotType = slotType,
            startedAtEpochMs = started,
            endedAtEpochMs = ended,
            plannedDurationMs = planned,
            actualFocusMs = actual,
            completed = j.getBoolean("completed"),
            timezoneId = zone,
            localDate = LocalDate.parse(j.getString("localDate")),
        )
    }

    data class ImportResult(val inserted: Int, val skipped: Int)

    private companion object {
        const val FORMAT = "aquihaytomate-backup"
        const val VERSION = 1
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
        const val MAX_SESSIONS = 200_000
    }
}
