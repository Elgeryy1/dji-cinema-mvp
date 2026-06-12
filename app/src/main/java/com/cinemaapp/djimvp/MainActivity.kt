package com.cinemaapp.djimvp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinemaapp.djimvp.ui.CineCamScreen
import com.cinemaapp.djimvp.ui.theme.DJICinemaMVPTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("CINECAM_PERMS", "MainActivity onCreate")
        enableEdgeToEdge()
        setContent {
            DJICinemaMVPTheme(dynamicColor = false) {
                val viewModel: MainViewModel = viewModel()
                CineCamScreen(viewModel)
            }
        }
    }
}
