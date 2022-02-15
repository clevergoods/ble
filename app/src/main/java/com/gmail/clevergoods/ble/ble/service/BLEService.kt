package com.gmail.clevergoods.ble.ble.service


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.*
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.gmail.clevergoods.*
import com.gmail.clevergoods.ble.ble.data.BLEAction.*
import com.gmail.clevergoods.ble.ble.data.BLEItem.*
import com.gmail.clevergoods.ble.ble.utils.BLEUtils
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.bleList
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.bleNames
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.connectAllEnabledBle
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.deserializeBleList
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.disconnectLeDevice
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.isForeground
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.reconnectBle
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.redBatteryLevel
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.startScanning
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.updateBle
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.updateBleNames
import com.gmail.clevergoods.location.LocationService
import com.gmail.clevergoods.utils.Notifier
import org.qtproject.qt5.android.bindings.QtService


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class BLEService : QtService() {
    companion object {
        private val LOG_TAG = "BLEService"
        var bleAddress: String? = null
        var notification:Notification? = null
    }
    
    private var outMessenger: Messenger? = null

    inner class BLEBinder : Binder() {
        fun getService(): BLEService? {
            return this@BLEService
        }
    }

    private val mBinder: IBinder = BLEBinder()
    override fun onBind(intent: Intent?): IBinder? {
        val extras: Bundle? = intent?.getExtras()
        extras?.let { outMessenger = it["MESSENGER"] as Messenger? }
        return mBinder
    }
    
    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = getNotificaation()
            startForeground(Notifier.NOTIFY_ID + 1, notification)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val ctx: Context = Utils.wrap(newBase)
        super.attachBaseContext(ctx)
    }

    private fun getNotificaation():Notification{
        val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel("wl_channel_id_02", "BLE Background Service")
                } else {
                    ""
                }

        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        notificationIntent.putExtra("qmlForm", "MapForm")
        val intent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val title = this.getString(R.string.sip_notification_title)
        val text = if (Utils.isServiceRunning(LocationService::class.java, this))
            this.getString(R.string.notification_text)
        else
            this.getString(R.string.ble_notification_text)

        val notification =
                notificationBuilder.setOngoing(true)
                        .setSmallIcon(R.drawable.notification_icon)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .setContentIntent(intent)
                        .build()
        return notification
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val chan = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
        )
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(Notifier.NOTIFY_ID + 1, notification)
        }
        WLog.i(LOG_TAG, "onStartCommand")
        if(intent == null){
            stopSelf()
            return START_STICKY
        }
        val actionAfterStartService = intent.getIntExtra(ACTION_AFTER_START.item, 0)

        if(intent.extras != null && intent.extras!!.containsKey(BLE_DEVICE.item)) {
            bleAddress = intent.getStringExtra(BLE_DEVICE.item)
        }

        if(intent.extras != null && intent.extras!!.containsKey(APP_FOREGROUND.item)) {
            val isFgr = intent.getBooleanExtra(APP_FOREGROUND.item, true)
            isForeground = isFgr
            PrefHelper.setIsForeground(isFgr)
        }

        if(intent.extras != null && intent.extras!!.containsKey(BLE_NAMES.item)){
            val bleNames = intent.getStringExtra(BLE_NAMES.item)
            WLog.d(LOG_TAG, "UPDATE_BLE_NAMES $bleNames")
            if (!bleNames.isNullOrEmpty()) BLEUtils.updateBleNames(bleNames)
        }
        
        WLog.i(LOG_TAG, "actionAfterStartService=" + actionAfterStartService)

        if (actionAfterStartService == CONNECT_BLE.act) {
            connectBle(bleAddress)
        }

        if (actionAfterStartService == CONNECT_ALL_ENABLED_BLE.act) {
            connectAllEnabledBle(this)
        }

        if (actionAfterStartService == DISCONNECT_BLE.act) {
            if (!bleAddress.isNullOrEmpty()) {
                WLog.d(LOG_TAG, "DISCONNECT_BLE ${(bleAddress != null && !bleAddress!!.isEmpty())}")
                disconnectBle(bleAddress)
            }
        }

        if (actionAfterStartService == FIND_BLE.act) {
            WLog.d(LOG_TAG, "FIND_BLE ${!bleAddress.isNullOrEmpty()} ")
            reconnectBle(this, bleAddress)
        }

        if (actionAfterStartService == READ_BATTERY_LEVEL.act) {
            WLog.d(LOG_TAG, "READ_BATTERY_LEVEL ${!bleAddress.isNullOrEmpty()} ")
            redBatteryLevel(this, bleAddress)
        }

        if (actionAfterStartService == CLEAN_BLE.act) {
            BLEUtils.cleanBleDevices(this, bleAddress)
        }

        if (actionAfterStartService == UPDATE_BLE.act) {
            val bleJson = intent.getStringExtra(BLE_JSON.item)
            WLog.d(LOG_TAG, "UPDATE_BLE_LIST $bleJson")
            if (bleJson != null) updateBle(this, bleJson)
        }

        if (actionAfterStartService == REMOVE_BLE.act) {
            BLEUtils.removeBle(this, bleAddress)
        }

        if (actionAfterStartService == MONITOR_BLE.act) {

        }

        if(bleList.isEmpty()){
            deserializeBleList(this)
        }
        if(bleNames.isEmpty()){
            val jsonString = PrefHelper.getBleNames()
            if(jsonString != null){
                updateBleNames(jsonString)
            }
        }

        return START_STICKY
    }

    fun disconnectBle(bleAddress: String?) {
        disconnectLeDevice(this, bleAddress)
    }

    fun connectBle(bleAddress: String?) {
        startScanning(this, bleAddress)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        //.bmodel.getApplicationContext().getSharedPreferences("myPrefs_capture_gps_per_hour", Context.MODE_PRIVATE);
        WLog.d(LOG_TAG, "TASK REMOVED")
    }

    override fun onDestroy() {
        stopForeground(true)
        WLog.d(LOG_TAG, "BLESERVICE STOP")
        //Toast.makeText(this, "BLE Service done", Toast.LENGTH_SHORT).show()
    }

}
