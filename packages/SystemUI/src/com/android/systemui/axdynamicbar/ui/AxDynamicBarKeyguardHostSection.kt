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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.compose.theme.PlatformTheme
import com.android.systemui.axdynamicbar.ui.compose.AxDynamicBarKeyguardChip
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/**
 * Hosts the Dynamic Bar media player on top of the lockscreen.
 *
 * The host is intentionally full-screen: the compact chip sits at the bottom of the lockscreen,
 * while tapping it renders the expanded player over the whole host. The composable only installs
 * pointer handlers on the chip and the expanded panel, so the rest of the lockscreen keeps
 * receiving touches normally.
 */
class AxDynamicBarKeyguardHostSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: AxDynamicBarChipViewModel,
) : KeyguardSection() {
    private val hostId = R.id.ax_dynamic_bar_keyguard_host

    override fun addViews(constraintLayout: ConstraintLayout) {
        val host =
            ComposeView(context).apply {
                id = hostId
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
                isFocusable = false
                isFocusableInTouchMode = false
                setContent {
                    PlatformTheme {
                        AxDynamicBarKeyguardChip(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            mediaOnly = true,
                        )
                    }
                }
            }
        constraintLayout.addView(host)
    }

    override fun bindData(constraintLayout: ConstraintLayout) = Unit

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            connect(hostId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(hostId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(hostId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(hostId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainWidth(hostId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(hostId, ConstraintSet.MATCH_CONSTRAINT)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.findViewById<ComposeView>(hostId)?.let { host ->
            constraintLayout.removeView(host)
        }
    }
}
