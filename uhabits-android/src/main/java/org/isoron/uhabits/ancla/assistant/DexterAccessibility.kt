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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Dexter's hands outside Ancla, once the user turns it on in Android's accessibility settings:
 * reads what's on screen, taps, types, scrolls and uses the system buttons, only when the user asks
 * by voice. Every action shows a small "Dexter" label on screen. It never acts with the phone
 * locked, in banking, payment or password apps, or on a screen with a password field, and it
 * never types into one.
 */
class DexterAccessibility : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var badge: TextView? = null

    /** Elements of the last screen read, by the number Dexter saw. */
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()
    private var lastPackage = ""

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        hideBadge()
        super.onDestroy()
    }

    // --- Called by AnclaActions, from the assistant's thread ---

    class Refused(message: String) : Exception(message)

    private fun locked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun checkAllowed(): AccessibilityNodeInfo {
        if (locked()) throw Refused("El teléfono está bloqueado: desbloquéalo para que pueda usar la pantalla.")
        val root = rootInActiveWindow ?: throw Refused("No puedo ver la pantalla ahora mismo.")
        val pkg = root.packageName?.toString().orEmpty()
        if (PhoneSafety.isSensitiveApp(pkg)) throw Refused("Por seguridad no toco nada en apps de bancos, pagos, contraseñas, instalación de apps o permisos. Hazlo tú.")
        // Not even Ancla itself: screen text could otherwise steer Dexter into its own settings.
        if (pkg == packageName) throw Refused("Dentro de Ancla uso mis propias herramientas, no la pantalla.")
        return root
    }

    /** The current screen as numbered elements for the assistant. */
    fun readScreen(): String = onMain {
        val root = checkAllowed()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 0)
        lastNodes = nodes
        lastPackage = root.packageName?.toString().orEmpty()
        PhoneSafety.describeScreen(appLabel(lastPackage), nodes.map(::toScreenNode))
    }

    /** Taps element [index] of the last screen read, or the first element whose text contains [text]. */
    fun tap(index: Int?, text: String?, longPress: Boolean): String = onMain {
        val root = checkAllowed()
        if (hasPassword(root)) throw Refused("Esta pantalla pide una contraseña: por seguridad no toco nada aquí.")
        val node = target(root, index, text) ?: throw Refused("No encuentro ese elemento en la pantalla. Léela otra vez.")
        val label = labelOf(node)
        val clickable = clickableAncestor(node)
        show(if (longPress) "Dexter mantiene «$label»" else "Dexter toca «$label»")
        val done = clickable?.performAction(if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) == true ||
            gesture(center(node), if (longPress) 800 else 60)
        if (done) "Toqué «$label»." else "No pude tocar «$label»."
    }

    /** Label of what [tap] would press, to decide whether it needs confirmation. */
    fun labelFor(index: Int?, text: String?): String? = onMain {
        val root = checkAllowed()
        target(root, index, text)?.let { node -> labelOf(clickableAncestor(node) ?: node) + " " + labelOf(node) }
    }

    /** Writes [value] into element [index] (or the focused or first text field), replacing what it had. */
    fun type(index: Int?, value: String): String = onMain {
        val root = checkAllowed()
        if (hasPassword(root)) throw Refused("Esta pantalla pide una contraseña: no escribo en ella.")
        val field = index?.let { lastNodes.getOrNull(it) }?.takeIf { it.isEditable }
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstEditable(root)
            ?: throw Refused("No hay un campo de texto en la pantalla.")
        if (field.isPassword) throw Refused("No escribo contraseñas.")
        show("Dexter escribe")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Escribí el texto." else "No pude escribir ahí."
    }

    fun scroll(forward: Boolean): String = onMain {
        val root = checkAllowed()
        show(if (forward) "Dexter baja" else "Dexter sube")
        val list = largestScrollable(root)
        val done = list?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true || swipe(forward)
        if (done) "Listo." else "No hay nada que desplazar."
    }

    /** System buttons and panels. Some (home, notifications, screenshot) are fine with the phone locked. */
    fun global(action: String): String = onMain {
        val (id, label, whileLocked) = when (action) {
            "back" -> Triple(GLOBAL_ACTION_BACK, "atrás", false)
            "home" -> Triple(GLOBAL_ACTION_HOME, "inicio", true)
            "recents" -> Triple(GLOBAL_ACTION_RECENTS, "apps recientes", false)
            "notifications" -> Triple(GLOBAL_ACTION_NOTIFICATIONS, "notificaciones", false)
            "quick_settings" -> Triple(GLOBAL_ACTION_QUICK_SETTINGS, "ajustes rápidos", false)
            "lock_screen" -> Triple(GLOBAL_ACTION_LOCK_SCREEN, "bloquear", true)
            "screenshot" -> Triple(GLOBAL_ACTION_TAKE_SCREENSHOT, "captura", false)
            else -> throw Refused("No conozco esa acción.")
        }
        if (!whileLocked && locked()) throw Refused("El teléfono está bloqueado: desbloquéalo primero.")
        show("Dexter: $label")
        if (performGlobalAction(id)) "Hecho: $label." else "No pude hacer: $label."
    }

    // --- Screen helpers ---

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 40 || out.size >= MAX_NODES) return
        if (node.isVisibleToUser && (labelOf(node).isNotBlank() || node.isClickable || node.isEditable || node.isScrollable)) out.add(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    @Suppress("DEPRECATION") // isChecked: the replacement needs API 36
    private fun toScreenNode(n: AccessibilityNodeInfo) = PhoneSafety.ScreenNode(
        text = if (n.isPassword) "" else labelOf(n),
        clickable = n.isClickable || n.isLongClickable,
        editable = n.isEditable,
        scrollable = n.isScrollable,
        password = n.isPassword,
        checked = if (n.isCheckable) n.isChecked else null
    )

    private fun labelOf(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString()).firstOrNull { it.isNotBlank() }.orEmpty()

    private fun target(root: AccessibilityNodeInfo, index: Int?, text: String?): AccessibilityNodeInfo? {
        if (index != null) {
            // Numbers refer to the last screen read: only valid while it's still the same app.
            if (root.packageName?.toString() != lastPackage) return null
            return lastNodes.getOrNull(index)?.takeIf { it.refresh() }
        }
        val wanted = PhoneSafety.norm(text ?: return null)
        if (wanted.isEmpty()) return null
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all, 0)
        return all.firstOrNull { PhoneSafety.norm(labelOf(it)) == wanted }
            ?: all.firstOrNull { PhoneSafety.norm(labelOf(it)).contains(wanted) && (it.isClickable || clickableAncestor(it) != null) }
            ?: all.firstOrNull { PhoneSafety.norm(labelOf(it)).contains(wanted) }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable || n.isLongClickable) return n
            n = n.parent
            hops++
        }
        return null
    }

    private fun hasPassword(root: AccessibilityNodeInfo): Boolean {
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all, 0)
        return all.any { it.isPassword }
    }

    private fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all, 0)
        return all.firstOrNull { it.isEditable && !it.isPassword }
    }

    private fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all, 0)
        return all.filter { it.isScrollable }.maxByOrNull { r -> Rect().also { r.getBoundsInScreen(it) }.let { it.width() * it.height() } }
    }

    private fun center(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }.let { it.exactCenterX() to it.exactCenterY() }

    private fun gesture(at: Pair<Float, Float>, millis: Long): Boolean {
        val path = Path().apply { moveTo(at.first, at.second) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, millis)).build())
    }

    private fun swipe(forward: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (from, to) = if (forward) dm.heightPixels * 0.7f to dm.heightPixels * 0.3f else dm.heightPixels * 0.3f to dm.heightPixels * 0.7f
        val path = Path().apply {
            moveTo(x, from)
            lineTo(x, to)
        }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build())
    }

    private fun dispatch(g: GestureDescription) = dispatchGesture(g, null, null)

    private fun appLabel(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    // --- The on-screen label: the user always sees when Dexter acts ---

    private fun show(text: String) {
        val wm = getSystemService(WindowManager::class.java) ?: return
        val view = badge ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply {
                cornerRadius = 40 * resources.displayMetrics.density
                setColor(0xE64F3FB5.toInt())
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (48 * resources.displayMetrics.density).toInt()
            }
            runCatching { wm.addView(this, params) }
            badge = this
        }
        view.text = "🤖 $text"
        main.removeCallbacks(hide)
        main.postDelayed(hide, 2500)
    }

    private val hide = Runnable { hideBadge() }

    private fun hideBadge() {
        badge?.let { runCatching { getSystemService(WindowManager::class.java)?.removeView(it) } }
        badge = null
    }

    /** Runs [block] on the main thread and waits for it (accessibility calls belong there). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            done.countDown()
        }
        if (!done.await(10, TimeUnit.SECONDS)) throw Refused("La pantalla no respondió.")
        return result!!.getOrThrow()
    }

    companion object {
        private const val MAX_NODES = 150

        @Volatile var instance: DexterAccessibility? = null
            private set

        /** Turned on in Android's accessibility settings (and connected). */
        fun enabled(context: Context): Boolean {
            if (instance != null) return true
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return flat.split(':').any { it.endsWith("/" + DexterAccessibility::class.java.name) || it.endsWith("/.ancla.assistant.DexterAccessibility") }
        }
    }
}
