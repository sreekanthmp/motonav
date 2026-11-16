package com.motonav.prototype

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager

/**
 * The Navigation SDK binds to this service after
 * navigator.registerServiceForNavUpdates(...) and sends a NavInfo about once a second.
 * Runs in the app's main process so it can hand data straight to [NavBridge].
 */
class NavInfoReceivingService : Service() {

    private val turnByTurnManager: TurnByTurnManager = TurnByTurnManager.createInstance()

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
            val navInfo = turnByTurnManager.readNavInfoFromBundle(msg.data)
            if (navInfo != null) NavBridge.onNavInfo(navInfo) else NavBridge.log("TBT feed: empty NavInfo message")
            true
        } else {
            NavBridge.log("TBT feed: unexpected message code ${msg.what}")
            false
        }
    })

    override fun onBind(intent: Intent?): IBinder {
        NavBridge.log("Google Navigation connected to the turn-by-turn service")
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        NavBridge.log("Google Navigation disconnected from the turn-by-turn service")
        return super.onUnbind(intent)
    }
}
