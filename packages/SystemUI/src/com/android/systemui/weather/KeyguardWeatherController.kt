/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.weather

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.util.yaap.OmniJawsClient
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds the lockscreen weather line to OmniJaws.
 *
 * This is intentionally independent from the Dynamic Bar master switch. The weather line is only
 * visible on the awake keyguard, never in AOD/doze, and disappears when OmniJaws has no data.
 */
class KeyguardWeatherController
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val settings: AxDynamicBarSettings,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val powerInteractor: PowerInteractor,
) : OmniJawsClient.OmniJawsObserver {
    private var root: View? = null
    private var icon: ImageView? = null
    private var text: TextView? = null

    private var weather: OmniJawsClient.WeatherInfo? = null

    private var started = false
    private var weatherEnabled = false
    private var isAwake = false
    private var isOnKeyguard = false
    private var isDozing = false
    private var isDreaming = false
    private var isBouncerShowing = false
    private var isFadingAway = false

    private var scope: CoroutineScope? = null
    private var settingsJob: Job? = null
    private var awakeJob: Job? = null
    private var refreshJob: Job? = null

    private val statusBarStateListener =
        object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                isOnKeyguard = newState == StatusBarState.KEYGUARD
                updateVisibility()
            }

            override fun onDozingChanged(dozing: Boolean) {
                isDozing = dozing
                updateVisibility()
            }

            override fun onDreamingChanged(dreaming: Boolean) {
                isDreaming = dreaming
                updateVisibility()
            }
        }

    private val keyguardStateListener =
        object : KeyguardStateController.Callback {
            override fun onPrimaryBouncerShowingChanged() {
                isBouncerShowing = keyguardStateController.isPrimaryBouncerShowing
                updateVisibility()
            }

            override fun onKeyguardFadingAwayChanged() {
                isFadingAway = keyguardStateController.isKeyguardFadingAway
                updateVisibility()
            }
        }

    fun bind(view: View) {
        root = view
        icon = view.findViewById(R.id.keyguard_weather_icon)
        text = view.findViewById(R.id.keyguard_weather_text)
        updateVisibility()
    }

    fun start() {
        if (started) return
        started = true

        isOnKeyguard = statusBarStateController.state == StatusBarState.KEYGUARD
        isDozing = statusBarStateController.isDozing
        isDreaming = statusBarStateController.isDreaming
        isBouncerShowing = keyguardStateController.isPrimaryBouncerShowing
        isFadingAway = keyguardStateController.isKeyguardFadingAway

        statusBarStateController.addCallback(statusBarStateListener)
        keyguardStateController.addCallback(keyguardStateListener)

        // Weather must keep working even when the Dynamic Bar manager is disabled.
        settings.init()

        val controllerScope =
            CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        scope = controllerScope

        settingsJob =
            controllerScope.launch {
                settings.isLockscreenWeatherEnabled.collect { enabled ->
                    setWeatherEnabled(enabled)
                }
            }

        awakeJob =
            controllerScope.launch {
                powerInteractor.isAwake.collect { awake ->
                    isAwake = awake
                    updateVisibility()
                }
            }

        updateVisibility()
    }

    fun stop() {
        if (!started) return
        started = false

        settingsJob?.cancel()
        awakeJob?.cancel()
        refreshJob?.cancel()
        settingsJob = null
        awakeJob = null
        refreshJob = null
        scope?.cancel()
        scope = null

        statusBarStateController.removeCallback(statusBarStateListener)
        keyguardStateController.removeCallback(keyguardStateListener)
        OmniJawsClient.get().removeObserver(context, this)

        root = null
        icon = null
        text = null
        weather = null
        weatherEnabled = false
    }

    override fun weatherUpdated() {
        refresh()
    }

    override fun weatherError(errorReason: Int) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            clearWeather()
        }
    }

    private fun setWeatherEnabled(enabled: Boolean) {
        if (weatherEnabled == enabled) return
        weatherEnabled = enabled

        if (enabled) {
            OmniJawsClient.get().addObserver(context, this)
            refresh()
        } else {
            OmniJawsClient.get().removeObserver(context, this)
            refreshJob?.cancel()
            refreshJob = null
            clearWeather()
        }
    }

    private fun refresh() {
        if (!weatherEnabled) return
        val controllerScope = scope ?: return

        refreshJob?.cancel()
        refreshJob =
            controllerScope.launch {
                val info =
                    withContext(Dispatchers.IO) {
                        val client = OmniJawsClient.get()
                        client.queryWeather(context)
                        client.weatherInfo
                    }

                if (!weatherEnabled) return@launch

                weather =
                    info?.takeIf {
                        !it.temp.isNullOrBlank() && it.temp != "-"
                    }

                if (weather == null) {
                    icon?.setImageDrawable(null)
                    text?.text = null
                    updateVisibility()
                    return@launch
                }

                val drawable =
                    withContext(Dispatchers.IO) {
                        OmniJawsClient.get().getWeatherConditionImage(
                            context,
                            weather?.conditionCode ?: -1,
                        )
                    }

                icon?.setImageDrawable(drawable)
                text?.text = buildWeatherText(weather)
                updateVisibility()
            }
    }

    private fun buildWeatherText(info: OmniJawsClient.WeatherInfo?): CharSequence {
        if (info == null) return ""
        val temperature = info.temp?.trim().orEmpty()
        val unit = info.tempUnits?.trim().orEmpty()
        return context.getString(
            R.string.ax_dynamic_bar_weather_text,
            temperature,
            unit,
            conditionLabel(info),
        )
    }

    private fun conditionLabel(info: OmniJawsClient.WeatherInfo): String {
        val label =
            when (info.conditionCode) {
                // Yahoo Weather condition codes, used by OmniJaws providers.
                in 0..4, in 37..38, 45, 47, in 900..902 ->
                    R.string.ax_dynamic_bar_weather_condition_storm
                in 5..12, 35, in 39..40, in 200..232, in 300..321, in 500..531 ->
                    R.string.ax_dynamic_bar_weather_condition_rain
                in 13..18, in 41..44, 46, in 600..622, 906 ->
                    R.string.ax_dynamic_bar_weather_condition_snow
                in 19..22, in 701..781 -> R.string.ax_dynamic_bar_weather_condition_mist
                in 23..24, 905 -> R.string.ax_dynamic_bar_weather_condition_wind
                in 26..30, in 801..804 -> R.string.ax_dynamic_bar_weather_condition_clouds
                in 31..34, 36, 800 -> R.string.ax_dynamic_bar_weather_condition_clear
                else -> 0
            }

        if (label != 0) return context.getString(label)

        val providerCondition = info.condition?.trim().orEmpty()
        return providerCondition.ifEmpty {
            context.getString(R.string.ax_dynamic_bar_weather_condition_unknown)
        }
    }

    private fun clearWeather() {
        weather = null
        icon?.setImageDrawable(null)
        text?.text = null
        updateVisibility()
    }

    private fun updateVisibility() {
        val shouldShow =
            weatherEnabled &&
                weather != null &&
                isAwake &&
                isOnKeyguard &&
                !isDozing &&
                !isDreaming &&
                !isBouncerShowing &&
                !isFadingAway
        root?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }
}
