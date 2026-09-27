package com.agit.app

import android.app.Application
import com.agit.app.data.AppContainer

class AgitApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}
