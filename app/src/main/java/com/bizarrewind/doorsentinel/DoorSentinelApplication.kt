package com.bizarrewind.doorsentinel

import android.app.Application
import com.google.firebase.FirebaseApp

class DoorSentinelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        SentinelPrefs.init(this)
    }
}
