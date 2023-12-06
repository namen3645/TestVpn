package com.myfast.openvpn.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import java.util.Locale

class TimerService : Service() {

    private var mStartTime: Long = 0

    private val binder: IBinder = LocalBinder()

    private var mCallback: TimerServiceCallback? = null

    var mDuration = "00:00:00"

    private val mHandler = Handler()

    private val mTimerRunnable: Runnable = object : Runnable {
        override fun run() {
            val timeElapsed = SystemClock.elapsedRealtime() - mStartTime
            val hours = (timeElapsed / 3600000).toInt()
            val minutes = ((timeElapsed / 60000) % 60).toInt()
            val seconds = ((timeElapsed / 1000) % 60).toInt()
            mDuration = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            updateDuration(mDuration)
            mHandler.postDelayed(this, 1000)
        }
    }

    private fun updateDuration(duration: String) {
        mCallback?.onDurationChanged(duration)
    }

    inner class LocalBinder : Binder() {
        fun getService(): TimerService {
            // Return this instance of LocalService so clients can call public methods.
            return this@TimerService
        }

        fun setCallback(callback: TimerServiceCallback) {
            mCallback = callback
        }
    }

    override fun onCreate() {
        super.onCreate()
        mStartTime = SystemClock.elapsedRealtime()
        mHandler.postDelayed(mTimerRunnable, 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY to indicate that the service should be restarted if it gets terminated
        return START_STICKY
    }

    interface TimerServiceCallback {
        fun onDurationChanged(duration: String)
    }

    override fun onDestroy() {
        super.onDestroy()
        mHandler.removeCallbacks(mTimerRunnable)
        stopForeground(true)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}