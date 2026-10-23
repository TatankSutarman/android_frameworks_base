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

package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.axdynamicbar.ui.compose.AxDynamicBarKeyguardChip
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine

private const val CHIP_ABOVE_LOCK_MARGIN_DP = 12f
private const val EXPANDED_BOTTOM_PROTECTION_DP = 16f
private const val UNSET = -1

/**
 * Hosts the Dynamic Bar media player on top of the lockscreen.
 *
 * The host follows the same layout contract as Infinity's keyguard chip section:
 * - Collapsed: the ComposeView wraps its content and sits between the lockscreen affordances
 *   (or above the device-entry icon for low UDFPS sensors).
 * - Expanded: the ComposeView is constrained between the visible clock and the device-entry icon,
 *   so the media panel cannot stretch from the status bar to the navigation bar.
 */
class AxDynamicBarKeyguardHostSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: AxDynamicBarChipViewModel,
    private val keyguardClockViewModel: KeyguardClockViewModel,
) : KeyguardSection() {
    private val hostId = R.id.ax_dynamic_bar_keyguard_host
    private var expansionHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        val host =
            ComposeView(context).apply {
                id = hostId
                layoutParams =
                    ConstraintLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
                isFocusable = false
                isFocusableInTouchMode = false
                setContent {
                    PlatformTheme {
                        AxDynamicBarKeyguardChip(
                            viewModel = viewModel,
                            mediaOnly = true,
                        )
                    }
                }
            }
        constraintLayout.addView(host)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val host: ComposeView = constraintLayout.findViewById(hostId) ?: return

        expansionHandle?.dispose()
        expansionHandle =
            host.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    combine(
                        viewModel.isLockscreenMediaExpanded,
                        viewModel.isLowUdfps,
                    ) { expanded, lowUdfps ->
                        expanded to lowUdfps
                    }.collect { (expanded, lowUdfps) ->
                        if (expanded) {
                            applyExpandedLp(host)
                        } else {
                            applyCollapsedLp(host, lowUdfps)
                        }
                    }
                }
            }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val expanded = viewModel.isLockscreenMediaExpanded.value
        val lowUdfps = viewModel.isLowUdfps.value
        val bottomProtectionPx = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        val chipAboveLockPx = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context)

        constraintSet.apply {
            clear(hostId, ConstraintSet.TOP)
            clear(hostId, ConstraintSet.BOTTOM)
            clear(hostId, ConstraintSet.START)
            clear(hostId, ConstraintSet.END)

            if (expanded) {
                constrainWidth(hostId, ConstraintSet.MATCH_CONSTRAINT)
                constrainHeight(hostId, ConstraintSet.MATCH_CONSTRAINT)
                connect(
                    hostId,
                    ConstraintSet.TOP,
                    if (keyguardClockViewModel.isLargeClockVisible.value) {
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
                    } else {
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
                    },
                    ConstraintSet.BOTTOM,
                )
                connect(
                    hostId,
                    ConstraintSet.BOTTOM,
                    R.id.device_entry_icon_view,
                    ConstraintSet.TOP,
                    bottomProtectionPx,
                )
                connect(hostId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(hostId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            } else if (lowUdfps) {
                constrainWidth(hostId, ViewGroup.LayoutParams.WRAP_CONTENT)
                constrainHeight(hostId, ViewGroup.LayoutParams.WRAP_CONTENT)
                connect(
                    hostId,
                    ConstraintSet.BOTTOM,
                    R.id.device_entry_icon_view,
                    ConstraintSet.TOP,
                    chipAboveLockPx,
                )
                connect(hostId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(hostId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            } else {
                constrainWidth(hostId, ViewGroup.LayoutParams.WRAP_CONTENT)
                constrainHeight(hostId, ViewGroup.LayoutParams.WRAP_CONTENT)
                connect(hostId, ConstraintSet.BOTTOM, R.id.start_button, ConstraintSet.BOTTOM)
                connect(hostId, ConstraintSet.START, R.id.start_button, ConstraintSet.END)
                connect(hostId, ConstraintSet.END, R.id.end_button, ConstraintSet.START)
            }
        }
    }

    private fun applyExpandedLp(host: View) {
        val lp = host.layoutParams as ConstraintLayout.LayoutParams
        lp.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        lp.height = 0
        lp.topToTop = UNSET
        lp.topToBottom =
            if (keyguardClockViewModel.isLargeClockVisible.value) {
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
            } else {
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
            }
        lp.topMargin = 0
        lp.bottomToBottom = UNSET
        lp.bottomToTop = R.id.device_entry_icon_view
        lp.bottomMargin = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        lp.startToEnd = UNSET
        lp.endToStart = UNSET
        host.layoutParams = lp
    }

    private fun applyCollapsedLp(host: View, lowUdfps: Boolean) {
        val lp = host.layoutParams as ConstraintLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.topToTop = UNSET
        lp.topToBottom = UNSET
        lp.topMargin = 0
        if (lowUdfps) {
            lp.bottomToTop = R.id.device_entry_icon_view
            lp.bottomToBottom = UNSET
            lp.bottomMargin = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context)
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.startToEnd = UNSET
            lp.endToStart = UNSET
        } else {
            lp.bottomToBottom = R.id.start_button
            lp.bottomToTop = UNSET
            lp.bottomMargin = 0
            lp.startToEnd = R.id.start_button
            lp.endToStart = R.id.end_button
            lp.startToStart = UNSET
            lp.endToEnd = UNSET
        }
        host.layoutParams = lp
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        expansionHandle?.dispose()
        expansionHandle = null
        constraintLayout.findViewById<ComposeView>(hostId)?.let { host ->
            constraintLayout.removeView(host)
        }
    }
}

private fun Float.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density + 0.5f).toInt()
