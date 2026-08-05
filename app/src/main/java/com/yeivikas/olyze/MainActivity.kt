package com.yeivikas.olyze

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.yeivikas.olyze.ui.screens.MainScreen
import com.yeivikas.olyze.ui.theme.OlyzeMusicStudioTheme

@RequiresApi(Build.VERSION_CODES.M)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            OlyzeMusicStudioTheme {
                MainScreen()
            }
        }
    }
}
