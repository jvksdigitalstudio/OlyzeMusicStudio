package com.yeivikas.olyze

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.yeivikas.olyze.ui.screens.MainScreen
import com.yeivikas.olyze.ui.theme.OlyzeMusicStudioTheme

@RequiresApi(Build.VERSION_CODES.M)
class MainActivity : ComponentActivity() {

    // Fixes a gap the MIDI Foundation phase's own report flagged and left
    // open: BLUETOOTH_CONNECT is declared in the manifest (needed for
    // Bluetooth MIDI device discovery via android.media.midi on API 31+)
    // but was never actually requested at runtime — a dangerous permission
    // declared without a request is simply inert on API 23+, so Bluetooth
    // MIDI controllers could silently fail to be discovered on Android 12+
    // even though the code path for them (AndroidMidiBackend) is otherwise
    // real and working. One-shot request at launch — no rationale UI, no
    // retry flow: if denied, USB MIDI (which needs no runtime permission)
    // and Bluetooth MIDI already paired at the OS level before this
    // permission existed still work; only fresh Bluetooth MIDI pairing on
    // API 31+ needs it.
    private val requestBluetoothConnect = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result intentionally unobserved here — AndroidMidiBackend's
           device discovery just won't surface Bluetooth MIDI devices if
           this was denied; no different in kind from a device that's
           simply not plugged in. Nothing in this app is built assuming
           Bluetooth MIDI specifically, so there's no broken feature to
           report back to the user for a denial — see the MIDI Foundation
           report for the full reasoning on why this was left open at the
           time, and why it's being closed now. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // BLUETOOTH_CONNECT is a runtime permission starting API 31.
            val alreadyGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (!alreadyGranted) requestBluetoothConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        setContent {
            OlyzeMusicStudioTheme {
                MainScreen()
            }
        }
    }
}
