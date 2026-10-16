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
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.AutomaticityPhase
import org.isoron.uhabits.core.models.CoachSuggestion
import org.isoron.uhabits.core.models.CoachSuggestionType
import org.isoron.uhabits.core.ui.screens.habits.show.views.AutomaticityCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.AutomaticityCardState
import org.isoron.uhabits.databinding.ShowHabitAutomaticityBinding
import org.isoron.uhabits.utils.InterfaceUtils.dpToPixels
import kotlin.math.roundToInt

class AutomaticityCardView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private val binding = ShowHabitAutomaticityBinding.inflate(LayoutInflater.from(context), this)
    private var presenter: AutomaticityCardPresenter? = null
    private var habitName = ""

    init {
        binding.selfReportButton.setOnClickListener { showSelfReportDialog() }
    }

    fun setListener(presenter: AutomaticityCardPresenter) {
        this.presenter = presenter
    }

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
        CoachSuggestionType.SELF_REPORT -> context.getString(R.string.coach_self_report)
    }

    private fun planText(state: AutomaticityCardState): String {
        val lines = mutableListOf<String>()
        if (state.anchor.isNotBlank()) {
            lines += if (state.place.isNotBlank()) {
                context.getString(R.string.plan_intention_place, state.anchor, state.habitName, state.place)
            } else {
                context.getString(R.string.plan_intention, state.anchor, state.habitName)
            }
        }
        if (state.tinyVersion.isNotBlank()) lines += context.getString(R.string.plan_tiny, state.tinyVersion)
        if (state.ifThen.isNotBlank()) lines += context.getString(R.string.plan_if_then, state.ifThen)
        return lines.joinToString("\n")
    }

    fun setState(state: AutomaticityCardState) {
        val color = state.theme.color(state.color).toInt()
        val r = state.report
        habitName = state.habitName
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

        val plan = planText(state)
        binding.planLabel.visibility = if (plan.isEmpty()) GONE else VISIBLE
        binding.planLabel.text = plan

        val tips = r.suggestions.map { coachText(it, state.unit) }.toMutableList()
        if (r.usesSelfReport) {
            tips += context.getString(
                R.string.self_report_blended,
                String.format("%.0f%%", r.modelAutomaticity * 100)
            )
        }
        binding.coachLabel.visibility = if (tips.isEmpty()) GONE else VISIBLE
        binding.coachLabel.text = tips.joinToString("\n\n")

        val level = r.suggestions.firstOrNull {
            it.type == CoachSuggestionType.LEVEL_UP || it.type == CoachSuggestionType.LEVEL_DOWN
        }
        if (level?.value != null && state.isNumerical) {
            val newTarget = level.value!!
            binding.applyLevelButton.visibility = VISIBLE
            binding.applyLevelButton.text = context.getString(
                R.string.apply_level_action,
                formatValue(newTarget),
                state.unit
            )
            binding.applyLevelButton.setOnClickListener { presenter?.onApplyLevel(newTarget) }
        } else {
            binding.applyLevelButton.visibility = GONE
            binding.applyLevelButton.setOnClickListener(null)
        }
        binding.selfReportButton.setTextColor(color)
        binding.applyLevelButton.setTextColor(color)
        postInvalidate()
    }

    private fun showSelfReportDialog() {
        val questions = listOf(
            R.string.srbai_q1,
            R.string.srbai_q2,
            R.string.srbai_q3,
            R.string.srbai_q4
        )
        val answers = IntArray(questions.size) { 4 }
        val pad = dpToPixels(context, 20f).toInt()
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.srbai_scale_hint)
                setTextColor(binding.phaseLabel.currentTextColor)
            }
        )
        questions.forEachIndexed { i, q ->
            val label = TextView(context).apply {
                text = context.getString(q, habitName)
                setPadding(0, pad / 2, 0, 0)
            }
            val value = TextView(context).apply { text = "${answers[i]} / 7" }
            val bar = SeekBar(context).apply {
                max = 6
                progress = answers[i] - 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        answers[i] = p + 1
                        value.text = "${answers[i]} / 7"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) = Unit
                })
            }
            container.addView(label)
            container.addView(bar)
            container.addView(value)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.srbai_title)
            .setView(android.widget.ScrollView(context).apply { addView(container) })
            .setPositiveButton(R.string.save) { _, _ -> presenter?.onSaveSelfReport(answers.toList()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
