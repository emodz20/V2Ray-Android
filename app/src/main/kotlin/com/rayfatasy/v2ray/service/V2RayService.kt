package com.rayfatasy.v2ray.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.IBinder
import android.os.StrictMode
import android.support.v7.app.NotificationCompat
import com.eightbitlab.rxbus.Bus
import com.eightbitlab.rxbus.registerInBus
import com.orhanobut.logger.Logger
import com.rayfatasy.v2ray.R
import com.rayfatasy.v2ray.event.*
import com.rayfatasy.v2ray.getV2RayApplication
import com.rayfatasy.v2ray.ui.MainActivity
import go.libv2ray.Libv2ray
import org.jetbrains.anko.notificationManager
import org.jetbrains.anko.startService

class V2RayService : Service() {
    companion object {
        const val NOTIFICATION_ID = 0
        const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
        const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 0
        const val ACTION_STOP_V2RAY = "com.rayfatasy.v2ray.action.STOP_V2RAY"

        var isServiceRunning = false
            private set

        fun startV2Ray(context: Context) {
            context.startService<V2RayService>()
        }

        fun stopV2Ray() {
            Bus.send(StopV2RayEvent)
        }

        fun checkStatusEvent(callback: (Boolean) -> Unit) {
            if (!isServiceRunning) {
                callback(false)
                return
            }
            Bus.send(CheckV2RayStatusEvent(callback))
        }
    }

    private val v2rayPoint = Libv2ray.NewV2RayPoint()
    private var vpnService: V2RayVpnService? = null
    private val v2rayCallback = V2RayCallback()
    private val stopV2RayReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            stopV2Ray()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        isServiceRunning = true

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        v2rayPoint.packageName = packageName

        Bus.observe<VpnServiceSendSelfEvent>()
                .subscribe {
                    vpnService = it.vpnService
                    vpnCheckIsReady()
                }
                .registerInBus(this)

        Bus.observe<StopV2RayEvent>()
                .subscribe {
                    stopV2Ray()
                }
                .registerInBus(this)

        Bus.observe<VpnServiceStatusEvent>()
                .filter { !it.isRunning }
                .subscribe { stopV2Ray() }
                .registerInBus(this)

        Bus.observe<CheckV2RayStatusEvent>()
                .subscribe {
                    val isRunning = vpnService != null
                            && v2rayPoint.isRunning
                            && VpnService.prepare(this) == null
                    it.callback(isRunning)
                }

        registerReceiver(stopV2RayReceiver, IntentFilter(ACTION_STOP_V2RAY))
    }

    override fun onDestroy() {
        super.onDestroy()
        Bus.unregister(this)
        unregisterReceiver(stopV2RayReceiver)
        isServiceRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startV2ray()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun vpnPrepare(): Int {
        startService<V2RayVpnService>()
        return 1
    }

    private fun vpnCheckIsReady() {
        val prepare = VpnService.prepare(this)

        if (prepare != null) {
            Bus.send(VpnPrepareEvent(prepare) {
                if (it)
                    vpnCheckIsReady()
                else
                    v2rayPoint.StopLoop()
            })
            return
        }

        if (this.vpnService != null) {
            v2rayPoint.VpnSupportReady()
            Bus.send(V2RayStatusEvent(true))
            showNotification()
        }
    }

    private fun startV2ray() {
        Logger.d(v2rayPoint)
        if (!v2rayPoint.isRunning) {
            v2rayPoint.callbacks = v2rayCallback
            v2rayPoint.vpnSupportSet = v2rayCallback
            v2rayPoint.configureFile = getV2RayApplication().configFile.absolutePath
            v2rayPoint.RunLoop()
        }
    }

    private fun stopV2Ray() {
        if (v2rayPoint.isRunning) {
            v2rayPoint.StopLoop()
        }
        vpnService = null
        Bus.send(V2RayStatusEvent(false))
        cancelNotification()
        stopSelf()
    }

    private fun showNotification() {
        val startMainIntent = Intent(applicationContext, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(applicationContext,
                NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        val stopV2RayIntent = Intent(ACTION_STOP_V2RAY)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(applicationContext,
                NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(applicationContext)
                .setSmallIcon(R.drawable.ic_action_logo)
                .setContentTitle(getString(R.string.notification_content_title))
                .setContentText(getString(R.string.notification_content_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(contentPendingIntent)
                .addAction(R.drawable.ic_close_grey_800_24dp,
                        getString(R.string.notification_action_stop_v2ray),
                        stopV2RayPendingIntent)
                .build()

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private inner class V2RayCallback : Libv2ray.V2RayCallbacks, Libv2ray.V2RayVPNServiceSupportsSet {
        override fun Shutdown() = 0L

        override fun GetVPNFd() = vpnService!!.getFd().toLong()

        override fun Prepare() = vpnPrepare().toLong()

        override fun Protect(l: Long) = (if (vpnService!!.protect(l.toInt())) 0 else 1).toLong()

        override fun OnEmitStatus(l: Long, s: String?): Long {
            Logger.d(s)
            return 0
        }

        override fun Setup(s: String): Long {
            Logger.d(s)
            try {
                vpnService!!.setup(s)
                return 0
            } catch (e: Exception) {
                e.printStackTrace()
                return -1
            }
        }
    }
}
