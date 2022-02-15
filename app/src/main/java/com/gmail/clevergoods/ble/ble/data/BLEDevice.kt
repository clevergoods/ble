package com.gmail.clevergoods.ble.ble.data

import android.bluetooth.*
import android.bluetooth.BluetoothDevice.*
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import android.content.Context
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.gmail.clevergoods.ble.ble.utils.BLEUtils
import com.gmail.clevergoods.R
import com.gmail.clevergoods.WLog
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.broadcastMessage
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.broadcastWarning
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.lastConnectedGatt
import com.gmail.clevergoods.ble.ble.utils.BLEUtils.Companion.startScanning
import org.json.JSONObject
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class BLEDevice(mContext: Context?) {

    companion object {}

    val context = mContext
    val LOG_TAG = "BLEDevice"
    val bleCharacteristics = mutableListOf<BluetoothGattCharacteristic>()
    val timeoutReconnects = mutableListOf<Long>()
    val batteryCharUUid = "00002a19-0000-1000-8000-00805f9b34fb"
    val CHAR_READ2 = "6e400003-b5a3-f393-e0a9-e50e26dcca9e"
    val Notify_Descriptor_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var isDescriptorWriting = false
    var connectionState: Int = BluetoothProfile.STATE_DISCONNECTED
    var device: BluetoothDevice? = null
    var bluetoothGatt: BluetoothGatt? = null
    var batteryCharacteristic: BluetoothGattCharacteristic? = null

    var address: String? = null
    var name: String? = null
    var enabled = true
    var loudMode = true // метка подключена иначе только сканирование

    var isLost: AtomicBoolean = AtomicBoolean(true)

    var isConnecting: AtomicBoolean = AtomicBoolean(false)
    var action = -1 // что сделать с меткой - найти, удалить ... BLEAction.FIND_BLE.act и т.д.
    var reconnectDelay = 5000L


    fun getSupportedGattServices(): List<BluetoothGattService>? {
        return if (bluetoothGatt == null) null else bluetoothGatt!!.services
    }

    fun setAllServicesNotification(enabled: Boolean) {
        val gattServices = getSupportedGattServices()
        if (gattServices == null) return
        WLog.i(LOG_TAG, "setAllServicesNotification")

        for (gattService in gattServices) {
            val uuid = gattService.uuid.toString()
            setServiceNotification(gattService, enabled)
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun displayCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val data = characteristic.value
        if (data != null && data.size > 0) {
            val stringBuilder = java.lang.StringBuilder(data.size)
            for (byteChar in data) stringBuilder.append(
                    String.format("%02X ", byteChar)
            )
            val msg = String(data) + "\n" + stringBuilder.toString()
            Log.i(LOG_TAG, "BLE characteristic ${characteristic.uuid}: $msg")
        }
    }

    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
        ) {
            connectionState = newState
            isConnecting.set(false)
            WLog.i(LOG_TAG, "onConnectionStateChange status = $status, newState = $newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (status == 8) {
                    connectBleDevice()
                    Handler(Looper.getMainLooper()).postDelayed({
                        WLog.i(LOG_TAG, "postdelayed processBleEvent connectionState=$connectionState")
                        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                            processBleEvent(BLEServiceAction.DISCONNECT_BLE_ACTION.item)
                        }
                    }, reconnectDelay)
                    //
                    WLog.i(LOG_TAG, "onConnectionStateChange DISCONNECT_BLE_ACTION status = $status, newState = $newState, timeoutReconnects.size = ${timeoutReconnects.size}")
                    val now = System.currentTimeMillis()
                    if (timeoutReconnects.isEmpty() || (!timeoutReconnects.isEmpty() && (now - timeoutReconnects.last() < 30000) && timeoutReconnects.size < 2)) {
                        timeoutReconnects.add(now)
                    } else {
                        timeoutReconnects.clear()
                    }
                    if (timeoutReconnects.size == 2 && !name.isNullOrEmpty()) {
                        WLog.i(LOG_TAG, "onConnectionStateChange RECONNECT timeoutReconnects.size = ${timeoutReconnects.size} name=$name")
                        broadcastWarning(context, name, context?.getString(R.string.ble_not_unstable), address, BLEWarning.TIME_OUT)
                        timeoutReconnects.clear()
                    }
                } else if (status != 0) {
                    reconnect(reconnectDelay)
                    processBleEvent(BLEServiceAction.LOST_BLE_ACTION.item)
                } else if (status == 0) {
                    if (action == BLEAction.RECONNECT_BLE.act) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                                connectBleDevice()
                            }
                        }, reconnectDelay)
                    } else {
                        if (loudMode) {
                            processBleEvent(BLEServiceAction.DISCONNECT_BLE_ACTION.item)
                        }
                        close()
                        if (!loudMode) {
                            context?.let { startScanning(it, address) }
                        }
                    }
                    action = -1
                    WLog.i(LOG_TAG, "onConnectionStateChange Disconnected from GATT server. status = $status")
                }
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                WLog.i(LOG_TAG, "onConnectionStateChange Connected to GATT server. status = $status")
                if (action == BLEAction.FIND_BLE.act) {
                    super.onConnectionStateChange(gatt, status, newState)
                    if (loudMode) {
                        reconnect(reconnectDelay)
                    } else {
                        disconnect()
                    }
                } else if (!loudMode) {
                    disconnect()
                } else {
                    processBleEvent(BLEServiceAction.CONNECT_BLE_ACTION.item)
                    discoverGattServices()
                    var lastDevice: BLEDevice? = null
                    if (!BLEUtils.bleList.isNullOrEmpty() && bluetoothGatt != null) {
                        lastDevice = BLEUtils.bleList.find {
                            bluetoothGatt!!.device.address.equals(it.address)
                        }
                    }
                    if (lastDevice == null || lastDevice.name == null) lastConnectedGatt = bluetoothGatt
                }
                //bluetoothGatt!!.readRemoteRssi()
            }
            super.onConnectionStateChange(gatt, status, newState)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            WLog.i(LOG_TAG, "onServicesDiscovered status = $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                WLog.i(LOG_TAG, "onServicesDiscovered received: $status")
                setAllServicesNotification(true)
            } else {
                WLog.i(LOG_TAG, "Service discovery failed");
                reconnect(0L)
            }
            super.onServicesDiscovered(gatt, status)
        }

        override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor, status: Int
        ) {
            isDescriptorWriting = false
            super.onDescriptorWrite(gatt, descriptor, status)
            WLog.i(LOG_TAG, "------------- onDescriptorWrite status: $status ${gatt.device.address} descriptor value = ${descriptor.value}")
        }

        override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                //displayCharacteristic(characteristic)
                WLog.i(LOG_TAG, "onCharacteristicRead status: $status")

                if (batteryCharacteristic != null && characteristic.uuid.equals(batteryCharacteristic?.uuid)) {
                    val batLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                    WLog.i(LOG_TAG, "onCharacteristicRead level: $batLevel ")
                    context?.let { BLEUtils.sendMessage(gatt.device.address, batLevel, BLEServiceAction.BATTERY_LEVEL_ACTION.item, it) }
                }
            }
            super.onCharacteristicRead(gatt, characteristic, status)
        }

        override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
        ) {
            WLog.i(LOG_TAG, "------------- onCharacteristicWrite status: $status")
            super.onCharacteristicWrite(gatt, characteristic, status)
        }

        override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
        ) {
            var charValue = 0
            if (characteristic.getUuid().toString().equals(CHAR_READ2)) {
                charValue = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
                WLog.i(LOG_TAG, "PLAY SOUND Char value = $charValue")
            }
            //1 - один раз нажата, 2 - двойное нажатие, 3 - выключили метку
            if (charValue == 1) {
                if (device == null) {
                    reconnect(0L)
                }
                if (name == null) {
                    context?.let { BLEUtils.sendMessage(device!!.address, BLEServiceAction.ACCEPT_NEW_BLE_ACTION.item, it) }
                } else {
                    context?.let {
                        if (device != null) {
                            BLEUtils.sendMessage(device!!.address, BLEServiceAction.ACCEPT_BLE_ACTION.item, it);
                            BLEUtils.loudSound(it)
                        }
                    }
                }
            }
            super.onCharacteristicChanged(gatt, characteristic)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            Handler(Looper.getMainLooper()).postDelayed({
                bluetoothGatt!!.readRemoteRssi()
            }, 3000)
            WLog.i(LOG_TAG, "onReadRemoteRssi rssi = $rssi; status = $status")
        }
    }

    fun connectBleDevice() {
        if (connectionState != BluetoothProfile.STATE_CONNECTED || bluetoothGatt == null) {
            val connectResult = connect()
        } else {
            WLog.i(LOG_TAG, "Connected to GATT server.")
            // Attempts to discover services after successful connection.
            //bluetoothGatt!!.discoverServices()
            discoverGattServices()
        }
    }

    fun discoverGattServices() {
        val bondstate = device?.getBondState()
        if (bondstate == BOND_NONE || bondstate == BOND_BONDED) {
            var delay = 0L
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N && bondstate == BOND_BONDED) {
                delay = 1000L
            }
            WLog.i(LOG_TAG, "discoverServices with delay = $delay")
            Handler(Looper.getMainLooper()).postDelayed({
                bluetoothGatt!!.discoverServices()
            }, delay)
        } else if (bondstate == BOND_BONDING) {
            WLog.i(LOG_TAG, "waiting for bonding to complete")
        }
    }

    fun close() {
        isDescriptorWriting = false
        if (bluetoothGatt == null) {
            WLog.i(LOG_TAG, "mBluetoothGatt not initialized")
            return
        } else {
            bluetoothGatt!!.close()
            bluetoothGatt = null
        }
        device = null;
        WLog.i(LOG_TAG, "mBluetoothGatt closed")
    }

    fun disconnect() {
        isDescriptorWriting = false
        if (bluetoothGatt == null) {
            connectionState = BluetoothProfile.STATE_DISCONNECTED
            WLog.i(LOG_TAG, "disconnect() BluetoothAdapter not initialized")
            if ((loudMode && action == BLEAction.RECONNECT_BLE.act) || !loudMode) {
                context?.let { startScanning(it, address) }
            }
            return
        }
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            connectionState = BluetoothProfile.STATE_DISCONNECTED
            close()
            if ((loudMode && action == BLEAction.RECONNECT_BLE.act) || !loudMode) {
                context?.let { startScanning(it, address) }
            }
            return
        }
        bluetoothGatt!!.disconnect()
        WLog.i(LOG_TAG, "disconnect() BluetoothGatt disconnected")
    }

    fun connect(): Boolean {
        if (isConnecting.get()) {
            return true
        }
        isConnecting.set(true)
        if (device == null || device!!.address == null) {
            WLog.i(LOG_TAG, "connect() BluetoothAdapter not initialized or unspecified address. device == null - ${device == null}  device!!.address == null - #{device!!.address == null}")
            context?.let { BLEUtils.sendMessage("", BLEServiceAction.RESTART_BLE_SERVICE.item, it) }
            isConnecting.set(false)
            return false
        }
        // Previously connected device.  Try to reconnect.
//        if (address != null && address.equals(device!!.address) && bluetoothGatt != null) {
//            WLog.i(LOG_TAG, "connect() Connecting to GATT server.")
//            connectionState = BluetoothProfile.STATE_CONNECTING
//            return bluetoothGatt!!.connect()
//        }

        device = BLEUtils.mBluetoothAdapter?.getRemoteDevice(device!!.address)
        if (device == null) {
            WLog.i(LOG_TAG, "connect() Device not found.  Unable to connect.")
            connectionState = BluetoothProfile.STATE_DISCONNECTED
            isConnecting.set(false)
            context?.let { BLEUtils.sendMessage("", BLEServiceAction.RESTART_BLE_SERVICE.item, it) }
            return false
        }

        connectionState = BluetoothProfile.STATE_CONNECTING
        val autoConnect = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WLog.i(LOG_TAG, "connect() No preferred coding when transmitting on the LE Coded PHY.")
            bluetoothGatt = device!!.connectGatt(context, autoConnect, mGattCallback, PHY_OPTION_NO_PREFERRED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WLog.i(LOG_TAG, "connect() Prefer LE transport for GATT connections to remote dual-mode devices")
            bluetoothGatt = device!!.connectGatt(context, autoConnect, mGattCallback, TRANSPORT_LE)
        } else {
            WLog.i(LOG_TAG, "connect() No preference of physical transport for GATT connections to remote dual-mode devices")
            bluetoothGatt = device!!.connectGatt(context, autoConnect, mGattCallback)
        }
        address = device!!.address
        return true
    }

    fun processBleEvent(bleAction: String) {
        context?.let {
            if (action != BLEAction.FIND_BLE.act && action != BLEAction.REMOVE_BLE.act) {
                address?.let { it1 -> BLEUtils.sendMessage(it1, bleAction, it) }
            }
            val wasLost = isLost.get()
            when (bleAction) {
                BLEServiceAction.CONNECT_BLE_ACTION.item -> isLost.set(false)
                BLEServiceAction.DISCONNECT_BLE_ACTION.item -> isLost.set(true)
                BLEServiceAction.ACCEPT_BLE_ACTION.item -> isLost.set(false)
                BLEServiceAction.FOUND_BLE_ACTION.item -> isLost.set(false)
                BLEServiceAction.LOST_BLE_ACTION.item -> isLost.set(true)
                BLEServiceAction.ACCEPT_NEW_BLE_ACTION.item -> isLost.set(false)
                else -> {
                }
            }

            WLog.i(LOG_TAG, "processBleEvent: bleAction = $bleAction, wasLost = $wasLost, isLost = $isLost ")

            if (action != BLEAction.FIND_BLE.act && action != BLEAction.REMOVE_BLE.act && name != null) {
                if (wasLost && !isLost.get()) {
                    broadcastMessage(context, name, it.getString(R.string.ble_found))
                } else if (!wasLost && isLost.get()) {
                    broadcastMessage(context, name, it.getString(R.string.ble_lost))
                }
                if (wasLost != isLost.get()) {
                    address?.let { BLEUtils.saveBle(context, it, isLost.get()) }
                }
            }
        }
    }

    private fun setServiceNotification(
            gattService: BluetoothGattService,
            enabled: Boolean
    ) {
        //val uuid = gattService.getUuid().toString()

        val gattCharacteristics: List<BluetoothGattCharacteristic> =
                gattService.getCharacteristics()

        for (gattCharacteristic in gattCharacteristics) {
            val writeType = gattCharacteristic.getWriteType();

            WLog.i(LOG_TAG, "gattService - ${gattService.uuid}  gattCharacteristic -  ${gattCharacteristic.uuid}")
            //readCharacteristic(gattCharacteristic)
            displayCharacteristic(gattCharacteristic)
            if (writeType == WRITE_TYPE_DEFAULT || writeType == WRITE_TYPE_NO_RESPONSE) {
                setCharacteristicNotification(gattCharacteristic, enabled)
            }
            if (gattCharacteristic.uuid.toString().equals(batteryCharUUid)) {
                batteryCharacteristic = gattCharacteristic
            }
            bleCharacteristics.add(gattCharacteristic)
            WLog.i(LOG_TAG, "gattService - ${gattService.uuid}  gattCharacteristic -  ${gattCharacteristic.uuid} gattCharacteristic.properties ${gattCharacteristic.properties} ")
        }
        //writeNextChar()
    }

    private fun writeNextChar() {
        if (bleCharacteristics.size > 0) {
            val gattCharacteristic = bleCharacteristics.removeAt(0)
            try {
                val ints = intArrayOf(0x01)
                val bytes = ints.foldIndexed(ByteArray(ints.size)) { i, a, v -> a.apply { set(i, v.toByte()) } }
                gattCharacteristic.let { writeCharacteristic(it, bytes) }
                WLog.i(LOG_TAG, "WRITE gattCharacteristic -  ${gattCharacteristic.uuid}")
            } catch (ex: Exception) {

            }
        }
    }

    private fun setCharacteristicNotification(
            characteristic: BluetoothGattCharacteristic,
            enabled: Boolean
    ) {
        if (BLEUtils.mBluetoothAdapter == null || bluetoothGatt == null) {
            WLog.i(LOG_TAG, "BluetoothAdapter not initialized")
            context?.let { BLEUtils.sendMessage("", BLEServiceAction.RESTART_BLE_SERVICE.item, it) }
            return
        }

        //characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        //mBluetoothGatt!!.writeCharacteristic(characteristic)
        if (((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                && characteristic.uuid.toString().equals(CHAR_READ2)
        ) {
            val success = bluetoothGatt!!.setCharacteristicNotification(characteristic, enabled)
            if (success) {
                val descriptor = characteristic.getDescriptor(Notify_Descriptor_UUID)
                if (descriptor != null) {
                    if (isDescriptorWriting) {
                        return
                    }
                    setNotifyDescriptor(enabled, descriptor)
                } else {
                    WLog.e(LOG_TAG, "Setting proper notification status for characteristic failed!")
                    //processBleEvent(BLEServiceAction.DISCONNECT_BLE_ACTION.item)
                    reconnect(0L)
                }
            }
        }
    }

    private fun setNotifyDescriptor(enabled: Boolean, descriptor: BluetoothGattDescriptor) {
        if (device == null) reconnect(0L)
        val value =
                if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        //ENABLE_INDICATION_VALUE ENABLE_NOTIFICATION_VALUE
        descriptor.value = value
        isDescriptorWriting = true
        val descriptorSuccess = bluetoothGatt!!.writeDescriptor(descriptor)
        WLog.d(LOG_TAG, "setNotifyDescriptor writeDescriptor ${device!!.address} $descriptorSuccess")
        if (!descriptorSuccess) {
            isDescriptorWriting = false
            reconnect(0)
            //connectionState = BluetoothProfile.STATE_DISCONNECTED
            //processBleEvent(DISCONNECT_BLE_ACTION)
            WLog.e(LOG_TAG, "Setting proper notification status for characteristic failed!")
        } else {
            //processBleEvent(BLEServiceAction.CONNECT_BLE_ACTION.item)
            connectionState = BluetoothProfile.STATE_CONNECTED
            WLog.d(LOG_TAG, "setNotifyDescriptor CONNECT_BLE_ACTION ${device!!.address}")
        }
    }

    fun writeCharacteristic(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
    ) {
        if (bluetoothGatt == null || bluetoothGatt == null) {
            WLog.i(LOG_TAG, "BluetoothAdapter not initialized")
            context?.let { BLEUtils.sendMessage("", BLEServiceAction.RESTART_BLE_SERVICE.item, it) }
            return
        }
        WLog.i("WRITE", characteristic.uuid.toString() + " : " + (BLEUtils.bytesToHex(value) ?: 0))
        characteristic.value = value
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        bluetoothGatt!!.writeCharacteristic(characteristic)
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        if (BLEUtils.mBluetoothAdapter == null || bluetoothGatt == null) {
            WLog.i(LOG_TAG, "BluetoothAdapter not initialized")
            context?.let { BLEUtils.sendMessage("", BLEServiceAction.RESTART_BLE_SERVICE.item, it) }
            return
        }
        try {
            bluetoothGatt!!.readCharacteristic(characteristic)
        } catch (e: Exception) {
            if (e is DeadObjectException) {
                WLog.i(LOG_TAG, "should either restart bluetooth or their phone. ${e.localizedMessage}")
            } else {
                WLog.i(LOG_TAG, e.localizedMessage)
            }
            context?.let { BLEUtils.sendMessage("", BLEServiceAction.RESTART_BLE_SERVICE.item, it) }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BLEDevice

        if (address != other.address) return false

        return true
    }

    override fun hashCode(): Int {
        return device?.address.hashCode()
    }

    fun reconnect(delay: Long) {
        reconnectDelay = delay
        action = BLEAction.RECONNECT_BLE.act
        disconnect()
    }

    fun serialize(): String {
        val bleJson = JSONObject()
        bleJson.put("address", address)
        bleJson.put("loudMode", loudMode)
        bleJson.put("name", name)
        bleJson.put("enabled", enabled)
        bleJson.put("lost", isLost.get())
        return bleJson.toString()
    }

    fun deserialize(bleJson:JSONObject){
        address = bleJson.getString("address")
        loudMode = bleJson.getBoolean("loudMode")
        name = bleJson.getString("name")
        enabled = bleJson.getBoolean("enabled")
        isLost.set(bleJson.getBoolean("lost"))
    }
}
