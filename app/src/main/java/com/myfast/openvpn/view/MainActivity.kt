package com.myfast.openvpn.view

import android.R
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.myfast.openvpn.data.ConnectState
import com.myfast.openvpn.databinding.ActivityMainBinding
import com.myfast.openvpn.helper.InternetHelper
import com.myfast.openvpn.service.TimerService
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import de.blinkt.openvpn.core.VpnStatus
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader


class MainActivity : AppCompatActivity() {

    private lateinit var binding:ActivityMainBinding
    private var mHandler:Handler? = null
    private var vpnStart = false
    private var currentState:ConnectState = ConnectState.CONNECT
    private var auth_failed = false
    private var bindSuccess = false
    private var connection: InternetHelper? = null
    private val MSG_UPDATE_STATE = 0
    private val ICS_OPENVPN_PERMISSION = 7
    private val VPN_PERMISSION = 85
    private val NOTIFICATIONS_PERMISSION_REQUEST_CODE = 11
    protected var mService: IOpenVPNAPIService? = null
    protected var mTimerService: TimerService? = null
    private var mBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        connection = InternetHelper()
        binding.btnConnect.setOnClickListener {
            if (!bindSuccess) {
                bindService()
            } else {
                prepareVpn()
            }
        }
        binding.btnStop.setOnClickListener {
            stopVpn()
        }
        VpnStatus.initLogCache(this.codeCacheDir)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (mService != null) {
            unbindService();
        }
    }

    private fun unbindService() {
        unbindService(mConnection)
    }

    private fun bindService() {
        val icsopenvpnService = Intent(IOpenVPNAPIService::class.java.name)
        icsopenvpnService.`package` = "com.myfast.openvpn"
        bindService(icsopenvpnService, mConnection, Context.BIND_AUTO_CREATE)
    }

    fun getInternetStatus(): Boolean {
        return connection?.netCheck(this)!!
    }

    override fun onStart() {
        super.onStart()
        mHandler = Handler()
        binding.duration.text = "Duration: 00:00:00"
        bindService()
    }

    private fun prepareVpn() {
        if (!vpnStart) {
            if (getInternetStatus()) {
                val intent = mService?.prepareVPNService()
                if (intent != null) {
                    startActivityForResult(intent, VPN_PERMISSION)
                } else {
                    onActivityResult(VPN_PERMISSION, Activity.RESULT_OK, null)
                }
            } else {
                Toast.makeText(this, "Check Internet connection!", Toast.LENGTH_SHORT).show()
            }
        } else {
            stopVpn()
        }
    }

    private fun startVpn(fileName:String) {
        try {
            val fileData = readAssetFile(this, fileName)
            val profile = mService?.addNewVPNProfile("Test VPN", false, fileData)
            mService?.startProfile(profile?.mUUID)
            mService?.startVPN(fileData)
            status(ConnectState.CONNECTING)
            auth_failed = false
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun stopVpn(): Boolean {
        try {
            if (currentState == ConnectState.CONNECTED) {
                mService?.disconnect()
                status(ConnectState.CONNECT)
                vpnStart = false
                return true
            }
            return false
        } catch (e: RemoteException) {
            e.printStackTrace()
        }

        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ICS_OPENVPN_PERMISSION && resultCode == Activity.RESULT_OK) {
            try {
                bindSuccess = true
                mService?.registerStatusCallback(mCallback)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
        if (requestCode == VPN_PERMISSION && resultCode == Activity.RESULT_OK) {
            vpnStart = true
            startVpn("test.ovpn")
        }
    }

    private val mCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            val msg = mHandler?.obtainMessage(MSG_UPDATE_STATE, "$state|$message")

            if (state == "AUTH_FAILED" || state == "CONNECTRETRY") {
                auth_failed = true
            }

            println("STATE-------------------------: " + state)

            if (!auth_failed) {
                try {
                    if (state == "CONNECTED") {
                        runOnUiThread {
                            binding.anotherInfo.text = message
                        }
                        auth_failed = false
                        setStatus(state)
                        if (ActivityCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_PERMISSION_REQUEST_CODE)
                        }
                        bindTimerService();
                    } else {
                        unbindTimerService()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                msg?.sendToTarget()
            } else {
                setStatus("CONNECTRETRY")
            }
        }
    }

    private val mTimerServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            val binder = iBinder as TimerService.LocalBinder
            mTimerService = binder.getService()
            binder.setCallback(mTimerServiceCallback)
            mBound = true
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            // This will be called when the service is disconnected unexpectedly
            mBound = false
        }
    }

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mService = IOpenVPNAPIService.Stub.asInterface(service)
            try {
                val i = mService?.prepare(packageName)
                if (i != null) {
                    startActivityForResult(i, ICS_OPENVPN_PERMISSION)
                } else {
                    onActivityResult(ICS_OPENVPN_PERMISSION, Activity.RESULT_OK, null)
                }
            } catch (e: RemoteException) {
                vpnStart = false
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mService = null
        }
    }


    fun setStatus(connectionState: String) {
        runOnUiThread {
            when (connectionState) {
                "NOPROCESS" -> {
                    vpnStart = false
                    status(ConnectState.CONNECT)
                }

                "CONNECTED" -> {
                    vpnStart = true
                    status(ConnectState.CONNECTED)
                }

                "WAIT" -> {
                    status(ConnectState.CONNECTING)
                }

                "AUTH" -> {
                    status(ConnectState.CONNECTING)
                }

                "CONNECTRETRY" -> {
                    status(ConnectState.RETRY)
                    try {
                        vpnStart = false
                        mService?.disconnect()
                    } catch (ex: RemoteException) {
                        ex.printStackTrace()
                    }
                }

                "AUTH_FAILED" -> {
                    vpnStart = false
                    status(ConnectState.RETRY)
                }

                "EXITING" -> {
                    vpnStart = false
                    status(ConnectState.CONNECT)
                }

                else -> vpnStart = false
            }
        }
    }

    private fun status(status: ConnectState) {
        currentState = status
        when (status) {
            ConnectState.CONNECT -> {
                binding.connectionStatus.text = "No Connected"
            }
            ConnectState.CONNECTED -> {
                binding.connectionStatus.text = "Connected"
            }
            ConnectState.CONNECTING -> {
                binding.connectionStatus.text = "Connecting..."
            }
            ConnectState.RETRY -> {
                binding.connectionStatus.text = "Conection failed"
            }
        }
    }

    private val mTimerServiceCallback = object : TimerService.TimerServiceCallback {
        override fun onDurationChanged(duration: String) {
            if (mBound) {
                runOnUiThread {
                    binding.duration.text = "Duration: $duration"
                }
            }
        }
    }

    private fun bindTimerService() {
        val serviceIntent = Intent(this, TimerService::class.java)
        serviceIntent.setPackage("com.myfast.openvpn")
        bindService(serviceIntent, mTimerServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindTimerService() {
        if (mBound) {
            unbindService(mTimerServiceConnection)
            mBound = false
        }
    }

    fun readAssetFile(context: Context, fileName: String): String {
        val assetManager = context.assets
        val inputStream = assetManager.open(fileName)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line).append("\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return stringBuilder.toString()
    }



}