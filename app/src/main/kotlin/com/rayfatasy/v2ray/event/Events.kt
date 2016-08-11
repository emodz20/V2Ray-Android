package com.rayfatasy.v2ray.event

import android.content.Intent
import com.rayfatasy.v2ray.service.V2RayVpnService

object StopV2RayEvent

data class CheckV2RayStatusEvent(val callback: (Boolean) -> Unit)

data class V2RayStatusEvent(val isRunning: Boolean)

data class VpnServiceSendSelfEvent(val vpnService: V2RayVpnService)

data class VpnServiceStatusEvent(val isRunning: Boolean)

data class VpnPrepareEvent(val intent: Intent, val callback: (Boolean) -> Unit)
