/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.AutomaticityPhase
import org.isoron.uhabits.core.models.CoachSuggestion
import org.isoron.uhabits.core.models.CoachSuggestionType
import org.isoron.uhabits.core.ui.screens.habits.show.views.AutomaticityCardState
import org.isoron.uhabits.databinding.ShowHabitAutomaticityBinding
import kotlin.math.roundToInt

class AutomaticityCardView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private val binding = ShowHabitAutomaticityBinding.inflate(LayoutInflater.from(context), this)

    private fun phaseName(phase: AutomaticityPhase): String = context.getString(
        when (phase) {
            AutomaticityPhase.INITIATION -> R.string.phase_initiation
            AutomaticityPhase.LEARNING -> R.string.phase_learning
            AutomaticityPhase.STABILITY -> R.string.phase_stability
            AutomaticityPhase.AUTOMATIC -> R.string.phase_automatic
        }
    )

    private fun formatValue(v: Double): String =
        if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format("%.1f", v)

    private fun coachText(s: CoachSuggestion, unit: String): String = when (s.type) {
        CoachSuggestionType.KEY_DAY -> context.getString(R.string.coach_key_day)
        CoachSuggestionType.RESTART -> context.getString(R.string.coach_restart)
        CoachSuggestionType.LEVEL_UP -> context.getString(R.string.coach_level_up, formatValue(s.value ?: 0.0), unit)
        CoachSuggestionType.LEVEL_DOWN -> context.getString(R.string.coach_level_down, formatValue(s.value ?: 0.0), unit)
        CoachSuggestionType.FRESH_START -> context.getString(R.string.coach_fresh_start)
    }

    fun setState(state: AutomaticityCardState) {
        val color = state.theme.color(state.color).toInt()
        val r = state.report
        binding.title.setTextColor(color)
        binding.automaticityRing.setColor(color)
        binding.automaticityRing.setPercentage(r.automaticity.toFloat())
        binding.automaticityLabel.setTextColor(color)
        binding.automaticityLabel.text = String.format("%.0f%%", r.automaticity * 100)
        binding.phaseLabel.text = phaseName(r.phase)
        binding.flexStreakLabel.setTextColor(color)
        binding.flexStreakLabel.text = if (r.graceUsed) "${r.flexibleStreak}*" else r.flexibleStreak.toString()
        binding.completionLabel.setTextColor(color)
        binding.completionLabel.text = r.completionRate?.let { String.format("%.0f%%", it * 100) } ?: "—"
        binding.daysLeftLabel.setTextColor(color)
        binding.daysLeftLabel.text = if (r.repetitionsLeft == 0) "✓" else "≈${r.daysLeft}"

        val tips = r.suggestions.map { coachText(it, state.unit) }
        if (tips.isEmpty()) {
            binding.coachLabel.visibility = GONE
        } else {
            binding.coachLabel.visibility = VISIBLE
            binding.coachLabel.text = tips.joinToString("\n\n")
        }
        postInvalidate()
    }
}
