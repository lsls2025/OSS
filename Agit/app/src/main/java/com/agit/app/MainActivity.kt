package com.agit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.agit.app.ui.launchApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchApp(this)
    }
}
