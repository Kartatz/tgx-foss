package org.thunderdog.challegram.push

import android.content.Context
import tgx.bridge.DeviceTokenRetriever
import tgx.bridge.PushManagerBridge
import tgx.bridge.TokenRetrieverListener

class NoPushDeviceTokenRetriever : DeviceTokenRetriever("none") {
  override fun isAvailable(context: Context): Boolean = false

  override fun performInitialization(context: Context): Boolean {
    PushManagerBridge.log("Push notifications are disabled (FOSS build without Firebase Cloud Messaging).")
    return false
  }

  override fun fetchDeviceToken(context: Context, listener: TokenRetrieverListener) {
    listener.onTokenRetrievalError("NO_PUSH_SERVICE", null)
  }
}
