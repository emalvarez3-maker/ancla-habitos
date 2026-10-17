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

package org.isoron.uhabits.ancla.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R

/**
 * The Dexter keyboard: dictate into any app (hold the mic to talk, double tap for hands-free), or
 * switch to "Dexter writes" and describe the message you want. A real input method, so text goes
 * straight into the field with no accessibility service, and it never listens in password fields.
 */
class DexterKeyboardService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private val keys by lazy { SecureKeys(this) }
    private var status: TextView? = null
    private var micButton: TextView? = null
    private var modeButton: TextView? = null
    private var writeMode = false
    private var sensitiveField = false
    private var busy = false
    private var lastTap = 0L
    private var pressedAt = 0L
    private var holding = false

    private val dictation by lazy {
        Dictation(
            this,
            keys,
            onState = ::render,
            onTranscript = { text -> handleText(text) },
            onError = { message -> showStatus(message); resumeWake() },
            onAudio = { file ->
                val audio = file.readBytes().also { file.delete() }
                process { GeminiText.transcribe(keys.geminiKey, audio) }
            },
            preferGeminiAudio = true
        )
    }

    private val startHold = Runnable {
        holding = true
        begin(DictationMode.HOLD)
    }

    // --- Lifecycle ---

    override fun onCreateInputView(): View = buildView()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val klass = info.inputType and InputType.TYPE_MASK_CLASS
        sensitiveField = (klass == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
            (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        showStatus(getString(if (sensitiveField) R.string.keyboard_password else if (writeMode) R.string.keyboard_hint_write else R.string.keyboard_hint))
        micButton?.alpha = if (sensitiveField) 0.35f else 1f
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dictation.cancel()
        busy = false
        resumeWake()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        dictation.release()
        super.onDestroy()
    }

    // --- Dictation ---

    private fun micAllowed() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun begin(mode: DictationMode) {
        if (sensitiveField || busy) return
        if (!micAllowed()) {
            showStatus(getString(R.string.keyboard_need_mic))
            return
        }
        DexterService.instance?.pause() // the always-on listener must let go of the microphone
        dictation.start(mode)
    }

    private fun handleText(text: String) {
        if (writeMode) {
            if (keys.geminiKey.isBlank()) {
                showStatus(getString(R.string.keyboard_need_gemini))
                resumeWake()
                return
            }
            val before = currentInputConnection?.getTextBeforeCursor(600, 0)?.toString().orEmpty()
            process { GeminiText.write(keys.geminiKey, text, before) }
        } else {
            insert(text)
            resumeWake()
        }
    }

    /** Runs a Gemini call off the main thread and types its result. */
    private fun process(call: () -> String) {
        busy = true
        showStatus(getString(R.string.keyboard_working))
        Thread {
            val result = runCatching(call)
            main.post {
                busy = false
                result.onSuccess { insert(it) }.onFailure {
                    showStatus(getString(if ((it as? AgentException)?.kind == AgentException.Kind.RATE_LIMIT) R.string.voice_rate_limit else R.string.voice_failed))
                }
                resumeWake()
            }
        }.start()
    }

    private fun insert(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val spacer = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        ic.commitText(spacer + clean, 1)
        showStatus(getString(if (writeMode) R.string.keyboard_hint_write else R.string.keyboard_hint))
    }

    private fun resumeWake() {
        DexterService.instance?.resume()
    }

    private fun render(state: DictationState) {
        when (state) {
            is DictationState.Listening -> {
                showStatus(state.partial.ifBlank { getString(if (state.mode == DictationMode.HANDS_FREE) R.string.keyboard_listening_free else R.string.keyboard_listening) })
                micButton?.background = pill(ACCENT_LIVE)
            }
            DictationState.Transcribing -> {
                showStatus(getString(R.string.keyboard_working))
                micButton?.background = pill(ACCENT)
            }
            DictationState.Idle -> micButton?.background = pill(ACCENT)
        }
    }

    private fun showStatus(text: String) {
        status?.text = text
    }

    // --- View ---

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun pill(color: Int, radius: Float = 28f * resources.displayMetrics.density) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun key(label: String, weight: Float, description: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        contentDescription = description
        setTextColor(if (dark) Color.parseColor("#E6E3F2") else Color.parseColor("#211F33"))
        background = pill(if (dark) Color.parseColor("#24212F") else Color.parseColor("#FFFFFF"), dp(14).toFloat())
        layoutParams = LinearLayout.LayoutParams(0, dp(52), weight).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(12))
            setBackgroundColor(if (dark) Color.parseColor("#15131F") else Color.parseColor("#F4F3FB"))
        }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 3
            setTextColor(if (dark) Color.parseColor("#B9B4CC") else Color.parseColor("#5B5770"))
            setPadding(dp(8), 0, dp(8), dp(10))
            text = getString(R.string.keyboard_hint)
        }
        root.addView(status)

        modeButton = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                writeMode = !writeMode
                updateModeButton()
                showStatus(getString(if (writeMode) R.string.keyboard_hint_write else R.string.keyboard_hint))
            }
        }
        updateModeButton()
        root.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(modeButton)
                setPadding(0, 0, 0, dp(10))
            }
        )

        micButton = TextView(this).apply {
            text = "🎙"
            textSize = 30f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.keyboard_mic)
            background = pill(ACCENT)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 2.2f).apply { setMargins(dp(4), 0, dp(4), 0) }
            // Hold to talk; a quick double tap starts hands-free; a tap while listening stops.
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val now = System.currentTimeMillis()
                        if (dictation.isActive) {
                            dictation.stop()
                        } else if (now - lastTap < DOUBLE_TAP_MS) {
                            begin(DictationMode.HANDS_FREE)
                        } else {
                            pressedAt = now
                            holding = false
                            main.postDelayed(startHold, HOLD_MS)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(startHold)
                        if (holding) {
                            holding = false
                            dictation.stop()
                        } else if (System.currentTimeMillis() - pressedAt < HOLD_MS) {
                            lastTap = System.currentTimeMillis()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(key("🌐", 1f, getString(R.string.keyboard_switch)) { switchToNextInputMethod(false) })
        row.addView(key("✕", 1f, getString(R.string.keyboard_cancel)) { dictation.cancel(); busy = false; resumeWake(); showStatus(getString(R.string.keyboard_hint)) })
        row.addView(micButton)
        row.addView(key("␣", 1.2f, getString(R.string.keyboard_space)) { currentInputConnection?.commitText(" ", 1) })
        row.addView(key("⌫", 1f, getString(R.string.keyboard_delete)) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) })
        row.addView(key("⏎", 1f, getString(R.string.keyboard_enter)) { sendDefaultEditorAction(true) || run { sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER); true } })
        root.addView(row)
        return root
    }

    private fun updateModeButton() {
        modeButton?.apply {
            text = getString(if (writeMode) R.string.keyboard_mode_write else R.string.keyboard_mode_dictate)
            setTextColor(if (writeMode) Color.WHITE else if (dark) Color.parseColor("#C9C0FF") else Color.parseColor("#4B3BB5"))
            background = pill(if (writeMode) ACCENT_WRITE else if (dark) Color.parseColor("#2A2540") else Color.parseColor("#ECE8FF"))
        }
    }

    companion object {
        private const val HOLD_MS = 250L
        private const val DOUBLE_TAP_MS = 300L
        private val ACCENT = Color.parseColor("#8E7CF0")
        private val ACCENT_LIVE = Color.parseColor("#E5484D")
        private val ACCENT_WRITE = Color.parseColor("#2FB57E")
        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
    }
}
