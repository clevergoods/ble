package com.gmail.clevergoods.ble.ble.data

enum class BLEAction (val act: Int) {
    MONITOR_BLE(0),
    CONNECT_BLE(1),
    DISCONNECT_BLE(2),
    FIND_BLE(3),
    UPDATE_BLE(4),
    REMOVE_BLE(5),
    CLEAN_BLE(6),
    CONNECT_ALL_ENABLED_BLE(7),
    UPDATE_BLE_NAMES(8),
    READ_BATTERY_LEVEL(9),
    RECONNECT_BLE(10)
}