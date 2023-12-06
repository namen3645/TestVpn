package com.myfast.openvpn.helper

import android.content.Context
import android.net.ConnectivityManager

class InternetHelper {
    /**
     * Check internet status
     * @param context
     * @return: internet connection status
     */
    fun netCheck(context: Context): Boolean {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nInfo = cm.activeNetworkInfo
        return nInfo != null && nInfo.isConnectedOrConnecting
    }
}