package dev.lichboard.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.lichboard.lichboard

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = context.lichboard.settings.current
        if (settings.startOnBoot && settings.floatingEnabled && Settings.canDrawOverlays(context)) {
            // Android/OEMs may still block a background start. Never retry in a polling loop.
            FloatingService.start(context)
        }
    }
}
