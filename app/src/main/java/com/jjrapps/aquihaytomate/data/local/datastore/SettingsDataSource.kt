
package com.jjrapps.aquihaytomate.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jjrapps.aquihaytomate.domain.model.AlertSound
import com.jjrapps.aquihaytomate.domain.model.AppLanguage
import com.jjrapps.aquihaytomate.domain.model.KeepScreenOnMode
import com.jjrapps.aquihaytomate.domain.model.SlotType
import com.jjrapps.aquihaytomate.domain.model.TimerSettings
import com.jjrapps.aquihaytomate.domain.model.WidgetBackground
import com.jjrapps.aquihaytomate.di.SettingsPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User settings, in their own DataStore file. Kept separate from the timer state because the two have
 * completely different write rates and lifetimes: settings change a handful of times ever, the timer
 * state changes on every transition.
 *
 * Every setter coerces into the range declared in [TimerSettings]. Values are clamped on the way in
 * and on the way out, so a hand-edited file or a value written by an older build can never produce a
 * zero-length pomodoro.
 */
@Singleton
class SettingsDataSource @Inject constructor(
    @param:SettingsPreferences private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<TimerSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): TimerSettings = settings.first()

    suspend fun setDurationMinutes(type: SlotType, minutes: Int) {
        val key = when (type) {
            SlotType.FOCUS -> Keys.FOCUS_MINUTES
            SlotType.SHORT_BREAK -> Keys.SHORT_BREAK_MINUTES
            SlotType.LONG_BREAK -> Keys.LONG_BREAK_MINUTES
        }
        val range = TimerSettings.minutesRangeFor(type)
        dataStore.edit { it[key] = minutes.coerceIn(range) }
    }

    suspend fun setPomodorosPerCycle(count: Int) = edit(Keys.POMODOROS_PER_CYCLE) {
        count.coerceIn(TimerSettings.POMODOROS_PER_CYCLE_RANGE)
    }

    suspend fun setVibrationSeconds(seconds: Int) = edit(Keys.VIBRATION_SECONDS) {
        seconds.coerceIn(TimerSettings.VIBRATION_SECONDS_RANGE)
    }

    suspend fun setDailyGoal(pomodoros: Int) = edit(Keys.DAILY_GOAL) {
        pomodoros.coerceIn(TimerSettings.DAILY_GOAL_RANGE)
    }

    suspend fun setAutoStartBreak(enabled: Boolean) = edit(Keys.AUTO_START_BREAK) { enabled }

    suspend fun setAutoStartFocus(enabled: Boolean) = edit(Keys.AUTO_START_FOCUS) { enabled }

    suspend fun setKeepScreenOn(mode: KeepScreenOnMode) = edit(Keys.KEEP_SCREEN_ON) { mode.id }

    /** Read, decide and write in a single edit, so two quick taps cannot interleave. */
    suspend fun toggleKeepScreenOnAlways() {
        dataStore.edit { prefs ->
            val current = prefs.keepScreenOnMode(TimerSettings().keepScreenOn)
            val restore = prefs[Keys.KEEP_SCREEN_ON_RESTORE]?.let(KeepScreenOnMode::fromId)
            val toggle = current.toggleAlways(restore)
            prefs[Keys.KEEP_SCREEN_ON] = toggle.mode.id
            if (toggle.restore != null) {
                prefs[Keys.KEEP_SCREEN_ON_RESTORE] = toggle.restore.id
            } else {
                prefs.remove(Keys.KEEP_SCREEN_ON_RESTORE)
            }
        }
    }

    suspend fun setLiquidAnimationEnabled(enabled: Boolean) =
        edit(Keys.LIQUID_ANIMATION) { enabled }

    suspend fun setOnboardingDone(done: Boolean) = edit(Keys.ONBOARDING_DONE) { done }

    suspend fun setFocusAlertSound(sound: AlertSound) =
        edit(Keys.FOCUS_ALERT_SOUND) { sound.id }

    suspend fun setBreakAlertSound(sound: AlertSound) =
        edit(Keys.BREAK_ALERT_SOUND) { sound.id }

    suspend fun setFocusAlertRepeats(count: Int) = edit(Keys.FOCUS_ALERT_REPEATS) {
        count.coerceIn(TimerSettings.ALERT_REPEATS_RANGE)
    }

    suspend fun setBreakAlertRepeats(count: Int) = edit(Keys.BREAK_ALERT_REPEATS) {
        count.coerceIn(TimerSettings.ALERT_REPEATS_RANGE)
    }

    suspend fun setWidgetBackground(background: WidgetBackground) =
        edit(Keys.WIDGET_BACKGROUND) { background.id }

    suspend fun setLanguage(language: AppLanguage) = edit(Keys.LANGUAGE) { language.id }

    // Restore settings from an imported backup.
    suspend fun restore(settings: TimerSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.FOCUS_MINUTES] = settings.focusMinutes
            prefs[Keys.SHORT_BREAK_MINUTES] = settings.shortBreakMinutes
            prefs[Keys.LONG_BREAK_MINUTES] = settings.longBreakMinutes
            prefs[Keys.POMODOROS_PER_CYCLE] = settings.pomodorosPerCycle
            prefs[Keys.AUTO_START_BREAK] = settings.autoStartBreak
            prefs[Keys.AUTO_START_FOCUS] = settings.autoStartFocus
            prefs[Keys.FOCUS_ALERT_SOUND] = settings.focusAlertSound.id
            prefs[Keys.BREAK_ALERT_SOUND] = settings.breakAlertSound.id
            prefs[Keys.FOCUS_ALERT_REPEATS] = settings.focusAlertRepeats
            prefs[Keys.BREAK_ALERT_REPEATS] = settings.breakAlertRepeats
            prefs[Keys.VIBRATION_SECONDS] = settings.vibrationSeconds
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn.id
            prefs[Keys.DAILY_GOAL] = settings.dailyGoal
            prefs[Keys.WIDGET_BACKGROUND] = settings.widgetBackground.id
            prefs[Keys.LANGUAGE] = settings.language.id
            prefs[Keys.LIQUID_ANIMATION] = settings.liquidAnimationEnabled
            prefs[Keys.ONBOARDING_DONE] = settings.onboardingDone

            prefs.remove(Keys.RETIRED_AUTO_START_NEXT)
            prefs.remove(Keys.RETIRED_ALERT_SOUND)
            prefs.remove(Keys.RETIRED_KEEP_SCREEN_ON)
            prefs.remove(Keys.KEEP_SCREEN_ON_RESTORE)
        }
    }

    private suspend fun <T> edit(key: Preferences.Key<T>, value: () -> T) {
        dataStore.edit { it[key] = value() }
    }

    private fun Preferences.toSettings(): TimerSettings {
        val defaults = TimerSettings()
        return TimerSettings(
            focusMinutes = minutes(Keys.FOCUS_MINUTES, SlotType.FOCUS, defaults.focusMinutes),
            shortBreakMinutes = minutes(
                Keys.SHORT_BREAK_MINUTES,
                SlotType.SHORT_BREAK,
                defaults.shortBreakMinutes,
            ),
            longBreakMinutes = minutes(
                Keys.LONG_BREAK_MINUTES,
                SlotType.LONG_BREAK,
                defaults.longBreakMinutes,
            ),
            pomodorosPerCycle = (this[Keys.POMODOROS_PER_CYCLE] ?: defaults.pomodorosPerCycle)
                .coerceIn(TimerSettings.POMODOROS_PER_CYCLE_RANGE),
            autoStartBreak = this[Keys.AUTO_START_BREAK]
                ?: this[Keys.RETIRED_AUTO_START_NEXT]
                ?: defaults.autoStartBreak,
            autoStartFocus = this[Keys.AUTO_START_FOCUS]
                ?: this[Keys.RETIRED_AUTO_START_NEXT]
                ?: defaults.autoStartFocus,
            focusAlertSound = AlertSound.fromId(
                this[Keys.FOCUS_ALERT_SOUND] ?: this[Keys.RETIRED_ALERT_SOUND],
                fallback = defaults.focusAlertSound,
            ),
            breakAlertSound = AlertSound.fromId(
                this[Keys.BREAK_ALERT_SOUND] ?: this[Keys.RETIRED_ALERT_SOUND],
                fallback = defaults.breakAlertSound,
            ),
            focusAlertRepeats = (this[Keys.FOCUS_ALERT_REPEATS] ?: defaults.focusAlertRepeats)
                .coerceIn(TimerSettings.ALERT_REPEATS_RANGE),
            breakAlertRepeats = (this[Keys.BREAK_ALERT_REPEATS] ?: defaults.breakAlertRepeats)
                .coerceIn(TimerSettings.ALERT_REPEATS_RANGE),
            vibrationSeconds = (this[Keys.VIBRATION_SECONDS] ?: defaults.vibrationSeconds)
                .coerceIn(TimerSettings.VIBRATION_SECONDS_RANGE),
            keepScreenOn = keepScreenOnMode(defaults.keepScreenOn),
            dailyGoal = (this[Keys.DAILY_GOAL] ?: defaults.dailyGoal)
                .coerceIn(TimerSettings.DAILY_GOAL_RANGE),
            widgetBackground = WidgetBackground.fromId(this[Keys.WIDGET_BACKGROUND]),
            language = AppLanguage.fromId(this[Keys.LANGUAGE]),
            liquidAnimationEnabled = this[Keys.LIQUID_ANIMATION] ?: defaults.liquidAnimationEnabled,
            onboardingDone = this[Keys.ONBOARDING_DONE] ?: defaults.onboardingDone,
        )
    }

    private fun Preferences.keepScreenOnMode(default: KeepScreenOnMode): KeepScreenOnMode {
        val stored = this[Keys.KEEP_SCREEN_ON]
        if (stored != null) return KeepScreenOnMode.fromId(stored)
        return if (this[Keys.RETIRED_KEEP_SCREEN_ON] == true) KeepScreenOnMode.ALWAYS else default
    }

    private fun Preferences.minutes(
        key: Preferences.Key<Int>,
        type: SlotType,
        default: Int,
    ): Int = (this[key] ?: default).coerceIn(TimerSettings.minutesRangeFor(type))

    /** Preference keys are a storage contract; renaming one silently resets that setting. */
    private object Keys {
        val FOCUS_MINUTES = intPreferencesKey("focus_minutes")
        val SHORT_BREAK_MINUTES = intPreferencesKey("short_break_minutes")
        val LONG_BREAK_MINUTES = intPreferencesKey("long_break_minutes")
        val POMODOROS_PER_CYCLE = intPreferencesKey("pomodoros_per_cycle")
        val AUTO_START_BREAK = booleanPreferencesKey("auto_start_break")
        val AUTO_START_FOCUS = booleanPreferencesKey("auto_start_focus")
        val RETIRED_AUTO_START_NEXT = booleanPreferencesKey("auto_start_next")
        val FOCUS_ALERT_SOUND = stringPreferencesKey("focus_alert_sound")
        val BREAK_ALERT_SOUND = stringPreferencesKey("break_alert_sound")
        val FOCUS_ALERT_REPEATS = intPreferencesKey("focus_alert_repeats")
        val BREAK_ALERT_REPEATS = intPreferencesKey("break_alert_repeats")
        val RETIRED_ALERT_SOUND = stringPreferencesKey("alert_sound")
        val VIBRATION_SECONDS = intPreferencesKey("vibration_seconds")
        val KEEP_SCREEN_ON = stringPreferencesKey("keep_screen_on_mode")
        val RETIRED_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEEP_SCREEN_ON_RESTORE = stringPreferencesKey("keep_screen_on_restore")
        val DAILY_GOAL = intPreferencesKey("daily_goal")
        val WIDGET_BACKGROUND = stringPreferencesKey("widget_background")
        val LANGUAGE = stringPreferencesKey("language")
        val LIQUID_ANIMATION = booleanPreferencesKey("liquid_animation")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }
}
