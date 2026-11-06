package com.globalvpn.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        VpnManager.init(this)
    }
}
