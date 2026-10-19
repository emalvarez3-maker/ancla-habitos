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

package org.isoron.uhabits.ancla

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.SecureKeys
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.DexterOwl
import org.isoron.uhabits.ancla.ui.OwlState
import org.isoron.uhabits.ancla.ui.combinedClickableCompat

/**
 * Locks the app behind the phone's own fingerprint, face or PIN: nobody who picks up the unlocked
 * phone can open Dexter's notes, journal, chats or tasks. The lock comes back when the screen
 * turns off or the app has been in the background for [GRACE_MS]. While locked, the screens
 * underneath are hidden, and with [SecureKeys.secureScreens] they never show up in screenshots,
 * screen recordings or the recent-apps preview (FLAG_SECURE).
 *
 * A phone with no screen lock can't verify anyone, so the lock stays off there (and says so).
 */
object AppLock : Application.ActivityLifecycleCallbacks {
    private const val GRACE_MS = 60_000L

    @Volatile var unlocked = false
        private set
    private var started = 0
    private var backgroundSince = 0L

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        // The screen going off locks right away, even with the app open.
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unlocked = false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    /** True when the lock is on and the phone can check who is holding it. */
    fun required(context: Context): Boolean =
        SecureKeys(context).appLock && context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Whether the phone has a screen lock at all (fingerprint, face, PIN, pattern or password). */
    fun deviceSecure(context: Context) = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    internal fun markUnlocked() {
        unlocked = true
    }

    // DexterMicActivity shows nothing at all (it only hands the microphone back), so it needs no unlock.
    private fun locked(activity: Activity) =
        activity !is LockActivity && activity !is org.isoron.uhabits.ancla.assistant.DexterMicActivity && required(activity) && !unlocked

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (SecureKeys(activity).secureScreens) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0 && backgroundSince > 0 && SystemClock.elapsedRealtime() - backgroundSince > GRACE_MS) unlocked = false
    }

    override fun onActivityResumed(activity: Activity) {
        val content = activity.window.decorView
        if (locked(activity)) {
            content.visibility = View.INVISIBLE // nothing personal shows behind the prompt
            activity.startActivity(Intent(activity, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } else {
            content.visibility = View.VISIBLE
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (--started <= 0) {
            started = 0
            backgroundSince = SystemClock.elapsedRealtime()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** The lock screen: Dexter asleep and the phone's own fingerprint, face or PIN prompt. */
class LockActivity : AppCompatActivity() {
    private var cancel: CancellationSignal? = null

    /** Ask once each time the lock comes up; after a cancel, the button asks again. */
    private var autoPrompt = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnclaTheme {
                val c = Ancla.colors
                BackHandler { moveTaskToBack(true) } // backing out never reveals the screen behind
                Column(
                    Modifier.fillMaxSize().background(c.ground).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DexterOwl(OwlState.SLEEPING, Modifier.size(160.dp))
                    Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 24.dp))
                    Text(
                        stringResource(R.string.lock_text), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                    )
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(c.ink).combinedClickableCompat(Role.Button, ::prompt)
                            .padding(horizontal = 28.dp, vertical = 14.dp)
                    ) { Text(stringResource(R.string.lock_unlock), style = MaterialTheme.typography.labelLarge, color = c.onInk) }
                }
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        autoPrompt = true
    }

    override fun onResume() {
        super.onResume()
        when {
            !AppLock.required(this) || AppLock.unlocked -> finish()
            autoPrompt -> {
                autoPrompt = false
                prompt()
            }
        }
    }

    override fun onStop() {
        // Leaving the app drops a fingerprint prompt still on screen (the PIN screen is our own child).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cancel?.cancel()
            cancel = null
        }
        super.onStop()
    }

    private fun prompt() {
        if (cancel != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal().also { cancel = it }
            BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.lock_prompt_title))
                .setSubtitle(getString(R.string.lock_prompt_subtitle))
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(
                    signal, mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            cancel = null
                            AppLock.markUnlocked()
                            finish()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            cancel = null // stays locked; the button tries again
                        }
                    }
                )
        } else {
            // Android 9 and 10: the system's own "confirm your PIN, pattern or password" screen.
            @Suppress("DEPRECATION")
            val confirm = getSystemService(KeyguardManager::class.java)
                .createConfirmDeviceCredentialIntent(getString(R.string.lock_prompt_title), getString(R.string.lock_prompt_subtitle))
            if (confirm == null) {
                AppLock.markUnlocked()
                finish()
            } else {
                cancel = CancellationSignal()
                @Suppress("DEPRECATION")
                startActivityForResult(confirm, CONFIRM)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        cancel = null
        if (requestCode == CONFIRM && resultCode == RESULT_OK) {
            AppLock.markUnlocked()
            finish()
        }
    }

    private companion object {
        const val CONFIRM = 41
    }
}
