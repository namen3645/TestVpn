package com.myfast.openvpn.view

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.RemoteException
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


class MainActivity : AppCompatActivity(), Handler.Callback {

    private lateinit var binding:ActivityMainBinding

    private var connection: InternetHelper? = null
    private var mHandler:Handler? = null
    private var vpnStart = false

    private val MSG_UPDATE_STATE = 0
    private val ICS_OPENVPN_PERMISSION = 7
    private val NOTIFICATIONS_PERMISSION_REQUEST_CODE = 11

    private var mService: IOpenVPNAPIService? = null
    private var mTimerService: TimerService? = null
    private var mBound = false
    private var authFailed = false

    private var currentState:ConnectState = ConnectState.CONNECT


    private var auth_failed = false
    private var bindSuccess = false
    private val VPN_PERMISSION = 85

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        connection = InternetHelper()
        binding.btnConnect.setOnClickListener {
            if (vpnStart) {
                stopVpn()
            } else {
                try {
                    prepareVpn()
                } catch (e: RemoteException) {
                    println("openvpn initialization failed: " + e.message)
                    e.printStackTrace()
                }
            }
        }
        binding.btnStop.setOnClickListener {
            stopVpn()
        }
        VpnStatus.initLogCache(this.codeCacheDir)
    }

    override fun onResume() {
        super.onResume()
        bindService()
    }

    override fun onPause() {
        if (mService != null) {
            unbindService()
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
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
        mHandler = Handler(this)
        bindService()
    }

    private fun prepareVpn() {
        if (!vpnStart) {
            if (getInternetStatus()) {
                val intent = mService?.prepareVPNService()
                if (intent != null) {
                    startActivityForResult(intent, 1)
                } else {
                    startVpn("test.ovpn")
                }
                status(ConnectState.CONNECT)
            } else {
                println("you have no internet connection !!")
            }
        } else if (stopVpn()) {
            println("Disconnect Successfully")
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
            mService?.disconnect()
            status(ConnectState.CONNECT)
            vpnStart = false
            return true
        } catch (e: RemoteException) {
            println("openvpn disconnect failed: " + e.message)
            e.printStackTrace()
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ICS_OPENVPN_PERMISSION) {
            try {
                mService?.registerStatusCallback(mCallback)
            } catch (e: RemoteException) {
                println("openvpn status callback failed: " + e.message)
                e.printStackTrace()
            }
        }
    }

    private val mCallback: IOpenVPNStatusCallback.Stub = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String, state: String, message: String, level: String) {
            val msg = mHandler?.obtainMessage(MSG_UPDATE_STATE, "$state|$message")
            if (state == "AUTH_FAILED" || state == "CONNECTRETRY") {
                authFailed = true
            }
            if (!authFailed) {
                try {
                    setStatus(state)
                    updateConnectionStatus(state)
                } catch (e: java.lang.Exception) {
                    println("openvpn status callback failed: " + e.message)
                    e.printStackTrace()
                }
                msg?.sendToTarget()
            }
            if (authFailed) {
                binding.anotherInfo.text = "AUTHORIZATION FAILED!!"
                setStatus("CONNECTRETRY")
            }
            if (state == "CONNECTED") {
                authFailed = false
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATIONS_PERMISSION_REQUEST_CODE
                    )
                }
                bindTimerService()
            } else {
                unbindTimerService()
            }
        }
    }

    private fun updateConnectionStatus(state: String) {
        /*
        if (state != "NOPROCESS") {
            binding.connectionStatus.text = "state: $state"
        }
        */
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

    override fun handleMessage(msg: Message): Boolean {
        if (msg.what == MSG_UPDATE_STATE) {
            binding.anotherInfo.text = msg.obj as CharSequence
        }
        return true
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