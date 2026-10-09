package com.headless.example

import android.app.Application
import android.util.Log
import rikka.shizuku.Shizuku

class HeadlessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Shizuku.addBinderReceivedListenerSticky {
                Log.i("HeadlessApp", "Shizuku binder connected in Application")
            }
        } catch (e: Throwable) {
            Log.w("HeadlessApp", "Failed to add Shizuku binder listener", e)
        }
    }
}

