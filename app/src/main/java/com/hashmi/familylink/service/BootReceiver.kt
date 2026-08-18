package com.hashmi.familylink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hashmi.familylink.data.AppMode
import com.hashmi.familylink.data.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACCEPTED_ACTIONS) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val mode = UserPreferencesRepository(context).currentAppMode()
                Log.d(TAG, "Boot/replace received (${intent.action}), mode=$mode")
                when (mode) {
                    AppMode.CLIENT -> ClientLinkService.start(context)
                    AppMode.SERVER -> ServerLinkService.start(context)
                    AppMode.UNDEFINED -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume Family Link after boot", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val ACCEPTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
