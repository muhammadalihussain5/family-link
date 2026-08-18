package com.hashmi.familylink

import android.app.Application
import com.hashmi.familylink.service.LinkNotifications

class FamilyLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LinkNotifications.ensureChannels(this)
    }
}
