package com.gmail.clevergoods.ble.ble;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.gmail.clevergoods.MainActivity;
import com.gmail.clevergoods.Utils;
import com.gmail.clevergoods.WLog;

import androidx.annotation.RequiresApi;

import static com.gmail.clevergoods.MainActivity.isForeground;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class BLEServiceApi {

    private static String LOG_TAG = "BLEServiceApi";

    public static void stopBleService(Context ctx) {
        if (Utils.Companion.isServiceRunning(BLEService.class, ctx) || MainActivity.bleServiceStarted){
            Intent stopIntent = new Intent(ctx, BLEService.class);
            ctx.stopService(stopIntent);
            MainActivity.bleServiceStarted = false;
            WLog.i(LOG_TAG, "stop BLEService");
        }
    }

    public static void monitorBle(Context ctx, String bleAddress) {
        WLog.d(LOG_TAG, "monitorBle");
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.MONITOR_BLE.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), bleAddress);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void connectBle(Context ctx, String bleAddress) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.CONNECT_BLE.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), bleAddress);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void connectAllEnabledBle(Context ctx) {
        if(MainActivity.isWatchApp)return;
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.CONNECT_ALL_ENABLED_BLE.getAct());
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void disconnectBle(Context ctx, String bleAddress) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.DISCONNECT_BLE.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), bleAddress);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void findBle(Context ctx, String bleAddress) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.FIND_BLE.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), bleAddress);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void readBatteryLevel(Context ctx, String bleAddress) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.READ_BATTERY_LEVEL.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), bleAddress);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void updateBle(Context ctx, String bleJson) {
        if(MainActivity.isWatchApp)return;
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.UPDATE_BLE.getAct());
        intent.putExtra(BLEItem.BLE_JSON.getItem(), bleJson);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void updateBleNames(Context ctx, String bleJson) {
        if(MainActivity.isWatchApp)return;
        BLEUtils.Companion.updateBleNames(bleJson);
    }

    public static void cleanBle(Context ctx, String bleAddress) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.CLEAN_BLE.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), bleAddress);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void setIsForeground(Context ctx, boolean isForeground) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void removeAllBle(Context ctx) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.REMOVE_BLE.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), "");
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static void removeBle(Context ctx, String bleAddress) {
        Intent intent = new Intent(ctx, BLEService.class);
        intent.putExtra(BLEItem.ACTION_AFTER_START.getItem(), BLEAction.REMOVE_BLE.getAct());
        intent.putExtra(BLEItem.BLE_DEVICE.getItem(), bleAddress);
        intent.putExtra(BLEItem.APP_FOREGROUND.getItem(), isForeground);
        startService(ctx, intent);
    }

    public static int isBluetoothEnabled() {
        return BLEUtils.Companion.isBluetoothEnabled();
    }

    public static void restartBleService(Context ctx) {
        if (!Utils.Companion.isServiceRunning(BLEService.class, ctx) && !MainActivity.bleServiceStarted) {
            connectAllEnabledBle(ctx);
        } else {
            stopBleService(ctx);
            connectAllEnabledBle(ctx);
        }
    }

    private static void startService(Context ctx, Intent intent){
        if(BLEUtils.Companion.isBluetooth()) {
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startService(intent);
            MainActivity.bleServiceStarted = true;
            WLog.i(LOG_TAG, "START BleService");
        }
    }

}

