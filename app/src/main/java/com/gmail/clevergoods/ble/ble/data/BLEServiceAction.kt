package com.gmail.clevergoods.ble.ble.data

enum class BLEServiceAction (val item: String) {
    DISCONNECT_BLE_ACTION("com.gmail.clevergoods.action.BLE_DISCONNECT"),
    CONNECT_BLE_ACTION("com.gmail.clevergoods.action.BLE_CONNECT"),
    FOUND_BLE_ACTION("com.gmail.clevergoods.action.BLE_FOUND"),
    LOST_BLE_ACTION("com.gmail.clevergoods.action.BLE_LOST"),
    ACCEPT_BLE_ACTION("com.gmail.clevergoods.action.BLE_ACCEPT"),
    ACCEPT_NEW_BLE_ACTION("com.gmail.clevergoods.action.BLE_ACCEPT_NEW"),
    BATTERY_LEVEL_ACTION("com.gmail.clevergoods.action.BATTERY_LEVEL"),
    WARNING_ACTION("com.gmail.clevergoods.action.WARNING"),
    RESTART_BLE_SERVICE("com.gmail.clevergoods.action.RESTART_BLE_SERVICE"),
    STOP_BLE_SERVICE("com.gmail.clevergoods.action.STOP_BLE_SERVICE")
}