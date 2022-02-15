package com.gmail.clevergoods.ble.ble.utils

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.gmail.clevergoods.ble.ble.data.BLEAction
import com.gmail.clevergoods.ble.ble.data.BLEWarning
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.gmail.clevergoods.*
import com.gmail.clevergoods.ble.ble.data.BLEDevice
import com.gmail.clevergoods.ble.ble.data.BLEServiceAction
import com.gmail.clevergoods.utils.Notifier
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
@SuppressLint("MissingPermission")
class BLEUtils() {

    companion object {
        private val TAG = "BLEUtils"
        var isScanning: AtomicBoolean = AtomicBoolean(false)
        private var SCAN_PERIOD: Long = 6000L
        private var maxScanCounter = 4
        private var scanCounter = 0
        var isForeground: Boolean? = null
        var bleNames = mutableListOf<String>()
        var lastConnectedGatt: BluetoothGatt? = null
        var pendingScan = false
        var bleList = mutableListOf<BLEDevice>()
        var scanResults = mutableListOf<ScanResult>()

        fun isBluetooth(): Boolean {
            return mBluetoothAdapter != null && mBluetoothAdapter!!.isEnabled
        }

        fun isBluetoothEnabled(): Int {
            return if (mBluetoothAdapter == null) {
                2 // Device does not support Bluetooth
            } else if (!mBluetoothAdapter.isEnabled) {
                1 // Bluetooth is not enabled :)
            } else {
                0 // Bluetooth is enabled
            }
        }

        val mBluetoothAdapter: BluetoothAdapter? =
                BluetoothAdapter.getDefaultAdapter()

        val mBluetoothLeScanner: BluetoothLeScanner? =
                mBluetoothAdapter?.getBluetoothLeScanner()

        var context: Context? = null

        fun sendMessage(address: String?, action: String, ctx: Context) {
            WLog.d(TAG, "SendMessge Address = $address, Action = $action")
            val intent = Intent()
            intent.action = action
            intent.putExtra("bleAddress", address)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            ctx.sendBroadcast(intent)
        }

        fun sendMessage(address: String?, level: Int, action: String, ctx: Context) {
            val intent = Intent()
            intent.action = action
            intent.putExtra("bleAddress", address)
            intent.putExtra("level", level)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            WLog.i(TAG, "SendMessage level: $level $address}")
            ctx.applicationContext.sendBroadcast(intent)
        }

        fun bytesToHex(bytes: ByteArray): String {
            var st = ""
            for (b in bytes) {
                st = String.format("%02X", b)
            }
            return st
        }

        fun serializeBleList() {
            if (bleList.isNullOrEmpty()) {
                return
            }
            val jbleArray = JsonArray()
            for (ble in bleList) {
                val bleJson = ble.serialize()
                jbleArray.add(bleJson)
            }
            val jsonObject = JsonObject()
            jsonObject.add("bleList", jbleArray)
            val jsonString: String = jsonObject.toString()
            PrefHelper.setBleList(jsonString)
        }

        fun deserializeBleList(ctx: Context) {
            if (bleNames.isNullOrEmpty()) {
                val jbleNames = PrefHelper.getBleNames() ?: return
                updateBleNames(jbleNames)
            }
            if (isForeground == null) {
                isForeground = PrefHelper.getIsForeground()
            }
            val jsonString = PrefHelper.getBleList() ?: return
            val jObject = JSONObject(jsonString)
            val jArray: JSONArray = jObject.getJSONArray("bleList")
            if (jArray.length() > 0) {
                bleList.clear()
                for (i in 0 until jArray.length()) {
                    val bleDevice = BLEDevice(ctx)
                    val bleJson = JSONObject(jArray[i].toString())
                    bleDevice.deserialize(bleJson)
                    val device = bleList.find { bleDevice.address.equals(it.address) }
                    if (device == null) {
                        bleList.add(bleDevice)
                    }
                }
            }
        }

        fun updateBle(ctx: Context, ble: String) {
            WLog.d(TAG, "updateBleList $ble")
            val bleJson = JSONObject(ble)
            val oldLoudMode: Boolean
            val bleAddress = bleJson.get("address")
            val loudMode = bleJson.get("loudMode") as Boolean

            var device = bleList.find { bleAddress.equals(it.address) }
            if (device == null) {
                device = BLEDevice(ctx)
                oldLoudMode = loudMode
                bleList.add(device)
            } else {
                oldLoudMode = device.loudMode
            }
            device.deserialize(bleJson)
            if (oldLoudMode && !loudMode) {
                if (device.connectionState == BluetoothProfile.STATE_CONNECTED) {
                    device.disconnect()
                }
            } else if (!oldLoudMode && loudMode) {
                startScanning(ctx, device.address)
            }
            if (lastConnectedGatt != null && device.address.equals(lastConnectedGatt!!.device.address) && device.name != null) {
                WLog.d(TAG, "updateBle lastConnectedGatt = null");
                lastConnectedGatt = null
            }
            serializeBleList()
            WLog.d(TAG, "Added to bleList device = ${device.address}, loudMode = ${device.loudMode}, isLost = ${device.isLost.get()} enabled = ${device.enabled} goMonitoring() = ${goMonitoring()}")
        }

        fun updateBleNames(jsonString: String) {
            WLog.d(TAG, "updateBleNames $jsonString")
            PrefHelper.setBleNames(jsonString)
            val bleJson = JSONArray(jsonString)
            for (i in 0 until bleJson.length()) {
                val jsonObject = bleJson.getJSONObject(i)
                val name = jsonObject.optString("name")
                if (!bleNames.contains(name)) bleNames.add(name)
                WLog.d(TAG, "Allowed ble names $name")
            }
        }

        fun removeBle(ctx: Context, bleAddress: String?) {
            if (bleAddress.isNullOrEmpty()) {
                disconnectLeDevice(ctx, bleAddress)
                WLog.d(TAG, "remove all ble - stopScanLeDevice")
                bleList.clear()
            } else {
                val device = bleList.find { bleAddress.equals(it.address) }
                device?.let {
                    if (it.loudMode) {
                        it.action = BLEAction.REMOVE_BLE.act
                        it.disconnect()
                    }
                    bleList.remove(it)
                }
            }
            if (bleList.isNullOrEmpty()) {
                PrefHelper.removeBleList()
                context?.let { sendMessage("", BLEServiceAction.STOP_BLE_SERVICE.item, it) }
                WLog.d(TAG, "remove all ble stopBleService")
            }else{
                serializeBleList()
            }
        }

        fun setAllServicesNotification(enabled: Boolean) {
            for (bleDevice in bleList) {
                bleDevice.setAllServicesNotification(enabled)
            }
        }

        fun setAllServicesNotification(bleAddress: String, enabled: Boolean) {
            val device = bleList.find { bleAddress.equals(it.address) }
            device?.setAllServicesNotification(enabled)
        }

        private val mLeScanCallback: ScanCallback = object : ScanCallback() {
            override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult
            ) {
                if (bleNames.contains(result.device.name)) {
                    val existed = scanResults.find { it.device.address.equals(result.device.address) }
                    if (existed == null) {
                        WLog.i(TAG, "Обнаружена метка  ${result.device.address} rssi: ${result.rssi} isForeground = $isForeground")
                        scanResults.add(result)
                    }
                }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                if (!results.isEmpty()) {
                    for (result in results) {
                        scanResults.add(result)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                if (errorCode == 1) {
                    WLog.e(TAG, "onScanFailed error=$errorCode BLE is OFF")
                }
                if (errorCode != 1) {
                    WLog.e(TAG, "onScanFailed error=$errorCode")
                    context?.let { sendMessage("", BLEServiceAction.RESTART_BLE_SERVICE.item, it) }
                }

            }
        }

        fun reconnectBle(ctx: Context?, bleAddress: String?) {
            WLog.d(TAG, "reconnectBle $bleAddress")
            val device = bleList.find { bleAddress.equals(it.address) }
            device?.let {
                it.action = BLEAction.FIND_BLE.act
                ctx?.let { startScanning(it, bleAddress) }
            }
        }

        fun redBatteryLevel(ctx: Context?, bleAddress: String?) {
            val bleDevice = bleList.find { bleAddress.equals(it.address) }
            if (bleDevice?.batteryCharacteristic != null) {
                bleDevice.readCharacteristic(bleDevice.batteryCharacteristic)
            }
        }

        fun disconnectLeDevice(ctx: Context?, bleAddress: String?) {
            WLog.d(TAG, "stopScanLeDevice mScanning = $isScanning.get()  bleAddress = $bleAddress")
            stopScanning(ctx)
            if (bleAddress.isNullOrEmpty()) {
                for (bleDevice in bleList) {
                    if (bleDevice.loudMode) {
                        bleDevice.disconnect()
                    }
                }
            } else {
                val bleDevice = bleList.find { bleAddress.equals(it.address) }
                if (bleDevice != null) {
                    if (bleDevice.loudMode) {
                        bleDevice.disconnect()
                    }
                    WLog.d(TAG, "stopScanLeDevice disconnect and close of bleAddress = $bleAddress")
                }
            }
        }

        private fun stopScanning(ctx: Context?) {
            WLog.i("Scanning", "stop")
            if (isScanning.get()) {
                if (isBluetooth()) {
                    mBluetoothLeScanner?.stopScan(mLeScanCallback)
                }
                isScanning.set(false)
            }
        }

        fun connectAllEnabledBle(ctx: Context) {
            if (bleList.isNullOrEmpty()) {
                deserializeBleList(ctx)
            }else{
                for(ble in bleList){
                    if(ble.name.isNullOrEmpty()){
                        removeBle(ctx, ble.address)
                    }
                }
            }
            WLog.i(TAG, "connectAllEnabledBle: goConnecting() = ${goConnecting()}, goMonitoring()=${goMonitoring()}")
            if (goConnecting() || goMonitoring()) {
                startScanning(ctx, null)
            } else {
                context?.let { sendMessage("", BLEServiceAction.STOP_BLE_SERVICE.item, it) }
            }
        }

        private fun goMonitoring(): Boolean {
            return bleList.find { !it.loudMode && it.enabled } != null
        }

        private fun goConnecting(): Boolean {
            return bleList.find { it.loudMode && it.enabled } != null
        }

        fun startScanning(ctx: Context, bleAddress: String?) {
            context = ctx
            WLog.d(TAG, "startScanning isScanning = ${isScanning.get()}")
            if (!isScanning.get()) {
                if ((bleAddress != null && bleAddress.equals("new")) || goConnecting() || goMonitoring()) {
                    pendingScan = false
                    val oneBleDevice = bleList.find { it.address == bleAddress }
                    if (oneBleDevice != null && oneBleDevice.enabled && oneBleDevice.loudMode && oneBleDevice.device != null) {
                        if (oneBleDevice.connectionState == BluetoothProfile.STATE_CONNECTED && oneBleDevice.action == BLEAction.FIND_BLE.act) {
                            oneBleDevice.reconnect(5000L)
                        }
                        if (oneBleDevice.connectionState == BluetoothProfile.STATE_DISCONNECTED) {
                            oneBleDevice.connect()
                        }
                        return
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        stopScanning(ctx)

                        if (scanResults.size > 0 && !needScaningBle()) {
                            scanCounter = 0
                            processScanResult(ctx, bleAddress)
                        } else {
                            if (scanCounter < maxScanCounter) {
                                scanCounter += 1
                                startScanning(ctx, bleAddress)
                            } else {
                                scanCounter = 0
                                if (bleAddress.isNullOrEmpty() || bleAddress.equals("new")) {
                                    sendMessage("", BLEServiceAction.LOST_BLE_ACTION.item, ctx)
                                }
                                WLog.d(TAG, "scanResults = null")
                                if (!bleList.isNullOrEmpty()) {
                                    for (bleDevice in bleList) {
                                        if (!bleDevice.loudMode) {
                                            bleDevice.processBleEvent(BLEServiceAction.LOST_BLE_ACTION.item)
                                        }
                                    }
                                }
                            }
                        }

                        if ((needScaningBle() || goMonitoring()) && !pendingScan && scanCounter == 0) {
                            pendingScan = true
                            Handler(Looper.getMainLooper()).postDelayed({
                                ctx.let { startScanning(it, null) }
                                //ctx.let { BLEServiceApi.monitorBle(it, null) }
                            }, SCAN_PERIOD * 2)
                        }

                    }, SCAN_PERIOD)

                    //WLog.d(TAG, "scanBle mScanning = $mScanning  goMonitoring = $goMonitoring goConnecting = $goConnecting")
                    scanBle()
                }
            }
        }

        //сканировали и не обнаружили то что искали
        private fun needScaningBle(): Boolean {
            if (!bleList.isNullOrEmpty()) {
                for (bleDevice in bleList) {
                    if (bleDevice.enabled && (!bleDevice.loudMode || (bleDevice.loudMode && bleDevice.device == null))) {
                        scanResults.find { it.device.address.equals(bleDevice.address) }
                                ?: return true
                    }
                }
            }
            return false
        }

        private fun scanBle() {
            if (isBluetooth() && !isScanning.get()) {

                scanResults.clear()
                var scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
                SCAN_PERIOD = 6000L
                maxScanCounter = 4
//                if (!isForeground) {
//                    scanMode = ScanSettings.SCAN_MODE_LOW_POWER
//                    SCAN_PERIOD = 30000L
//                    maxScanCounter = 1
//                }
                //SCAN_MODE_LOW_POWER - in bckground
                //SCAN_MODE_LOW_LATENCY
                val settings = ScanSettings.Builder().setScanMode(scanMode).build()
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))).build()
                val filterList = mutableListOf<ScanFilter>()
                filterList.add(filter)//in bckground
                mBluetoothLeScanner?.startScan(filterList, settings, mLeScanCallback)
                //mBluetoothLeScanner?.startScan(mLeScanCallback)
                isScanning.set(true)
                WLog.i("Scanning", "scanMode = $scanMode, SCAN_PERIOD = $SCAN_PERIOD, maxScanCounter = $maxScanCounter")
            }
        }

        fun broadcastMessage(ctx: Context?, notificationTitle: String?, notificationMessage: String?) {
            WLog.d(TAG, "Send broadcast $notificationTitle >>>  $notificationMessage")
            ctx?.let{
                if (notificationTitle != null) {
                    val notificationIntent = Intent(ctx, MainActivity::class.java)
                    notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    notificationIntent.putExtra("qmlForm", "MapForm")
                    val intent = PendingIntent.getActivity(ctx, System.currentTimeMillis().toInt(), notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                    Notifier(it).notifyMessage(notificationTitle, notificationMessage, intent)
                }
            }
        }

        fun broadcastWarning(ctx: Context?, notificationTitle: String?, notificationMessage: String?, bleAddress: String?, action: BLEWarning) {
            WLog.d(TAG, "Send broadcast $notificationTitle >>>  $notificationMessage $bleAddress action = ${action.act}")
            ctx?.let {
                if (isForeground != null && !(isForeground as Boolean) && notificationTitle != null) {
                    val notificationIntent = Intent(ctx, MainActivity::class.java)
                    notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    if (action.equals(BLEWarning.TIME_OUT)) {
                        notificationIntent.putExtra("qmlForm", "ble/BleInfo")
                        notificationIntent.putExtra("params", bleAddress)
                    }
                    if (action.equals(BLEWarning.RESTART_SERVICE)) {
                        notificationIntent.putExtra("qmlForm", "mapForm")
                        notificationIntent.putExtra("params", "restartBleService")
                    }
                    val intent = PendingIntent.getActivity(ctx, System.currentTimeMillis().toInt(), notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                    Notifier(it).notifyWarningMessage(notificationTitle, notificationMessage, intent)
                }
                if (isForeground != null && isForeground as Boolean) {
                    sendMessage(bleAddress, action.act, BLEServiceAction.WARNING_ACTION.item, it)
                }
            }
        }

        private fun processScanResult(ctx: Context, bleAddress: String?) {
            if ("new".equals(bleAddress)) {
                val bleForProcessing = {
                    val sortedRessults = scanResults.sortedWith(compareBy { it.rssi })
                    sortedRessults[sortedRessults.size - 1]
                }
                val result = bleForProcessing.invoke()
                val device = createBleDevice(ctx, result)
                device.connectBleDevice()
            } else if (!bleAddress.isNullOrEmpty()) {
                val bleDevice = bleList.find { bleAddress.equals(it.address) }
                processOneScanResult(ctx, bleDevice)
            } else if (bleAddress.isNullOrEmpty()) {
                for (bleDevice in bleList) {
                    processOneScanResult(ctx, bleDevice)
                }
            }
        }

        private fun processOneScanResult(ctx: Context, device: BLEDevice?) {
            var bleDevice = device
            val bleAddress = bleDevice?.address

            val bleForProcessing = {
                scanResults.find { it.device.address.equals(bleAddress) }
            }
            val bleResult = bleForProcessing.invoke()

            WLog.i(TAG, "process Scanning result bleDevice.action = ${bleDevice?.action} bleDevice.loudMode=${bleDevice?.loudMode} ")

            if (bleResult == null) {
                if (bleDevice != null && (bleDevice.device == null || bleDevice.connectionState != BluetoothGatt.STATE_CONNECTED)) {
                    bleDevice.processBleEvent(BLEServiceAction.LOST_BLE_ACTION.item)
                } else if (bleDevice == null) {
                    sendMessage("", BLEServiceAction.LOST_BLE_ACTION.item, ctx)
                }
            } else {
                if (bleDevice == null || bleDevice.device == null) {
                    bleDevice = createBleDevice(ctx, bleResult)
                }
                if (bleDevice.device != null && bleDevice.enabled && bleDevice.loudMode && bleDevice.connectionState != BluetoothProfile.STATE_CONNECTED) {
                    bleDevice.connectBleDevice()
                }
                if (bleDevice.enabled && !bleDevice.loudMode) {
                    if (bleDevice.action == BLEAction.FIND_BLE.act) {
                        bleDevice.connectBleDevice()
                    } else {
                        bleDevice.processBleEvent(BLEServiceAction.FOUND_BLE_ACTION.item)
                    }
                }
            }
        }

        fun cleanBleDevices(ctx: Context?, bleAddress: String?) {
            if (bleAddress.isNullOrEmpty()) {
                for (bleDevice in bleList) {
                    bleDevice.device = null
                }

            } else if (bleAddress.equals("new") && lastConnectedGatt != null) {
                stopScanning(ctx)
                for (bleDevice in bleList) {
                    if (bleDevice.device?.address.equals(lastConnectedGatt?.device?.address) && bleDevice.name != null) {
                        WLog.d(TAG, "lastConnectedGatt Exists in bleList ${bleDevice.address}")
                        lastConnectedGatt = null
                        return
                    }
                }
                lastConnectedGatt?.disconnect()
                WLog.d(TAG, "lastConnectedGatt Not Exists in bleList DISCONNECT ${lastConnectedGatt!!.device.address}")
            } else {
                val bleDevice = bleList.find { bleAddress.equals(it.address) }
                if (bleDevice != null) {
                    bleDevice.device = null
                }
            }
        }

        fun createBleDevice(ctx: Context, result: ScanResult): BLEDevice {
            val newDevice = result.device
            val existedBleDevice = bleList.find { newDevice.address.equals(it.address) }
            if (existedBleDevice != null) {
                existedBleDevice.device = newDevice
                return existedBleDevice
            }
            val bleDevice = BLEDevice(ctx)
            bleDevice.device = newDevice
            bleDevice.address = newDevice.address
            bleList.add(bleDevice)
            WLog.d(TAG, "--------------bleList.add---${bleDevice.address}---");
            return bleDevice
        }


        private var audioManager: AudioManager? = null
        private var mediaPlayer: MediaPlayer? = null
        private val oldStreamVolume = 0

        fun loudSound(ctx: Context) {
            audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager;
            mediaPlayer = MediaPlayer();

            Handler(Looper.getMainLooper()).postDelayed({
                stopMediaPlayer();
            }, 1000)

            playSound(ctx);
            WLog.d(TAG, "--------------PLAY SOUND---------------------");

        }

        fun playSound(ctx: Context) {
            try {
                val oldStreamVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM);
                val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                if (maxVolume != null) {
                    audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, AudioManager.FLAG_PLAY_SOUND)
                };
                mediaPlayer?.setDataSource(ctx, Settings.System.DEFAULT_RINGTONE_URI);
                mediaPlayer?.setAudioStreamType(AudioManager.STREAM_ALARM);
                mediaPlayer?.prepare();
                mediaPlayer?.start();
                mediaPlayer?.setLooping(true);

            } catch (ex: Exception) {
                WLog.e(TAG, ex.toString());
            }
        }

        fun stopMediaPlayer() {
            mediaPlayer?.stop();
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, oldStreamVolume, AudioManager.FLAG_PLAY_SOUND);
        }

        fun saveBle(ctx: Context, bleAddress: String, lost: Boolean) {
            if (!Utils.isNetworkAvailable(ctx)) {
                return 
            }
            Thread{
                try {
                    val uuid = "ble_" + bleAddress.replace(":", "")
                    val message = JSONObject()
                    message.put("uuid", uuid)
                    message.put("address", bleAddress)
                    message.put("lost", lost)
                    WLServer.sendBleData(message);
                    WLog.d(TAG, "send ble event /ble/save?uuid=$uuid&address=$bleAddress&lost=$lost")
                } catch (e: Exception) {
                    WLog.e(TAG, "Ошибка отправки состояния BLE $bleAddress lost = $lost $e")
                }
            }.start()
        }


    }
}
