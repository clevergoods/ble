package com.gmail.clevergoods.ble.ble.data

enum class BLEItem (val item: String) {
    BLE_DEVICE("bleDevice"),
    BLE_JSON("updateBleList"),
    BLE_NAMES("updateBleNames"),
    ACTION_AFTER_START("actionAfterStartService"),
    APP_FOREGROUND("isForeground")
}