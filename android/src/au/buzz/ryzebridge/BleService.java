package au.buzz.ryzebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Headless BLE bridge for the Ryze Wave (UTE / GloryFit protocol). Driven from adb:
 *
 *   adb shell am start -n au.buzz.ryzebridge/.CmdActivity --es script "connect;readfeat;write a1;until a1 3000;disconnect"
 *   adb logcat -s RyzeBridge:*
 *
 * Script statements (separated by ';'):
 *   connect [MAC]        connect over LE (default MAC below), discover, MTU 247, enable notifications on 33F2 + 34F2
 *   disconnect           close the GATT link
 *   write HEX [data]     write to 33F1 (or 34F1 with "data")
 *   read cmd|data        GATT read of 33F1 (feature bitmap) or 34F1 (max length)
 *   readfeat             = read cmd
 *   until HEXPREFIX [MS] wait until a notification starting with HEXPREFIX arrives (default 10000 ms)
 *   wait MS              sleep
 *   mark                 discard notifications received so far (a following "until" only sees newer ones)
 *   prio high|balanced|low   requestConnectionPriority
 *   phy2m                setPreferredPhy(2M)
 *   mtu N                requestMtu
 *   gatt                 dump services/characteristics
 *   status               log connection state
 *   sportloop TYPE SECS  once a second send the FD 44 workout update (elapsed hh:mm:ss, zero distance/pace/cal)
 *
 * Every packet is logged as "TX 33F1 <hex>" / "RX 33F2 <hex>"; the script ends with "DONE" (or "ERR ...").
 */
public class BleService extends Service {
    static final String TAG = "RyzeBridge";
    static final String DEFAULT_MAC = "78:02:B7:37:91:E5";
    static UUID u16(String s) { return UUID.fromString("0000" + s + "-0000-1000-8000-00805f9b34fb"); }
    static final UUID CH_CMD_W = u16("33f1"), CH_CMD_N = u16("33f2"), CH_DATA_W = u16("34f1"), CH_DATA_N = u16("34f2"), CCCD = u16("2902");

    private HandlerThread thread;
    private Handler handler;
    private BluetoothGatt gatt;
    private volatile boolean connected;
    private volatile int mtu = 23;

    // one outstanding GATT operation at a time
    private final Object opLock = new Object();
    private boolean opDone;
    private int opStatus;
    private byte[] opValue;
    // notifications
    private final Object rxLock = new Object();
    private final List<String> rxLog = new ArrayList<>();
    private int rxMark = 0;            // index in rxLog where the next "until" starts looking (set at each write)

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("ble-bridge");
        thread.start();
        handler = new Handler(thread.getLooper());
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("bridge", "RyzeBridge", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, "bridge")
                .setContentTitle("RyzeBridge")
                .setContentText("BLE bridge running")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String script = intent.getStringExtra("script");
        if (script == null) {
            String cmd = intent.getStringExtra("cmd");
            String hex = intent.getStringExtra("hex");
            script = cmd == null ? "status" : (hex == null ? cmd : cmd + " " + hex);
        }
        final String s = script;
        handler.post(() -> runScript(s));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        closeGatt();
        thread.quitSafely();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ script
    private void runScript(String script) {
        Log.i(TAG, "SCRIPT " + script);
        try {
            for (String stmt : script.split(";")) {
                String[] p = stmt.trim().split("\\s+");
                if (p.length == 0 || p[0].isEmpty()) continue;
                switch (p[0].toLowerCase(Locale.ROOT)) {
                    case "connect":   doConnect(p.length > 1 ? p[1] : DEFAULT_MAC); break;
                    case "disconnect": closeGatt(); break;
                    case "write":     doWrite(p[1], p.length > 2 && p[2].equals("data")); break;
                    case "read":      doRead(p.length > 1 && p[1].equals("data")); break;
                    case "readfeat":  doRead(false); break;
                    case "until":     doUntil(p[1], p.length > 2 ? Long.parseLong(p[2]) : 10000); break;
                    case "wait":      Thread.sleep(Long.parseLong(p[1])); break;
                    case "mark":      synchronized (rxLock) { rxMark = rxLog.size(); } break;
                    case "prio":      doPrio(p.length > 1 ? p[1] : "high"); break;
                    case "phy2m":     doPhy2m(); break;
                    case "mtu":       doMtu(Integer.parseInt(p[1])); break;
                    case "gatt":      dumpGatt(); break;
                    case "sportloop": doSportLoop(Integer.parseInt(p[1]), Integer.parseInt(p[2])); break;
                    case "status":    Log.i(TAG, "STATUS connected=" + connected + " mtu=" + mtu); break;
                    default:          Log.w(TAG, "UNKNOWN " + stmt);
                }
            }
            Log.i(TAG, "DONE");
        } catch (Exception e) {
            Log.e(TAG, "ERR " + e);
        }
    }

    // ------------------------------------------------------------------ ops
    private void doConnect(String mac) throws Exception {
        if (connected && gatt != null) { Log.i(TAG, "STATE already connected"); return; }
        closeGatt();
        BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
        BluetoothDevice dev = ad.getRemoteDevice(mac);
        Log.i(TAG, "CONNECTING " + mac);
        synchronized (opLock) { opDone = false; }
        gatt = dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
        waitOp(30000, "connect");
        if (!connected) throw new Exception("connect failed");
        synchronized (opLock) { opDone = false; }
        gatt.discoverServices();
        waitOp(30000, "discover");
        doMtu(247);
        enableNotify(CH_CMD_N);
        enableNotify(CH_DATA_N);
        Log.i(TAG, "READY mtu=" + mtu);
    }

    private void closeGatt() {
        if (gatt != null) {
            try { gatt.disconnect(); Thread.sleep(300); gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
        connected = false;
    }

    private BluetoothGattCharacteristic ch(UUID uuid) throws Exception {
        for (BluetoothGattService s : gatt.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(uuid);
            if (c != null) return c;
        }
        throw new Exception("characteristic " + uuid + " not found");
    }

    private void doWrite(String hex, boolean data) throws Exception {
        if (gatt == null || !connected) throw new Exception("not connected");
        BluetoothGattCharacteristic c = ch(data ? CH_DATA_W : CH_CMD_W);
        byte[] v = hexToBytes(hex);
        int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        synchronized (opLock) { opDone = false; }
        synchronized (rxLock) { rxMark = rxLog.size(); }
        int rc;
        if (Build.VERSION.SDK_INT >= 33) {
            rc = gatt.writeCharacteristic(c, v, type);
        } else {
            c.setWriteType(type);
            c.setValue(v);
            rc = gatt.writeCharacteristic(c) ? 0 : -1;
        }
        Log.i(TAG, "TX " + (data ? "34F1" : "33F1") + " " + hex.toLowerCase(Locale.ROOT) + (rc == 0 ? "" : " rc=" + rc));
        waitOp(5000, "write");
    }

    private void doRead(boolean data) throws Exception {
        if (gatt == null || !connected) throw new Exception("not connected");
        BluetoothGattCharacteristic c = ch(data ? CH_DATA_W : CH_CMD_W);
        synchronized (opLock) { opDone = false; opValue = null; }
        gatt.readCharacteristic(c);
        waitOp(5000, "read");
        Log.i(TAG, "RD " + (data ? "34F1" : "33F1") + " " + (opValue == null ? "null" : bytesToHex(opValue)));
    }

    private void doUntil(String prefix, long ms) throws Exception {
        prefix = prefix.toLowerCase(Locale.ROOT);
        long end = System.currentTimeMillis() + ms;
        synchronized (rxLock) {
            while (true) {
                for (int i = rxMark; i < rxLog.size(); i++) {
                    if (rxLog.get(i).startsWith(prefix)) { rxMark = i + 1; Log.i(TAG, "UNTIL " + prefix + " ok"); return; }
                }
                rxMark = rxLog.size();
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                rxLock.wait(left);
            }
        }
        Log.w(TAG, "UNTIL " + prefix + " timeout");
    }

    private void doPrio(String p) {
        int prio = p.equals("low") ? BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                : p.equals("balanced") ? BluetoothGatt.CONNECTION_PRIORITY_BALANCED : BluetoothGatt.CONNECTION_PRIORITY_HIGH;
        boolean ok = gatt != null && gatt.requestConnectionPriority(prio);
        Log.i(TAG, "PRIO " + p + " " + ok);
    }

    private void doPhy2m() {
        if (gatt != null && Build.VERSION.SDK_INT >= 26) {
            gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED);
            Log.i(TAG, "PHY requested 2M");
        }
    }

    private void doMtu(int n) throws Exception {
        synchronized (opLock) { opDone = false; }
        gatt.requestMtu(n);
        waitOp(5000, "mtu");
    }

    private void enableNotify(UUID uuid) throws Exception {
        BluetoothGattCharacteristic c = ch(uuid);
        gatt.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) throw new Exception("no CCCD on " + uuid);
        synchronized (opLock) { opDone = false; }
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(d);
        }
        waitOp(5000, "cccd");
        Log.i(TAG, "NOTIFY " + uuid.toString().substring(4, 8) + " on");
    }

    /** Phone -> watch live workout metrics, as the vendor app does once per second (FD 44 type ivl hh mm ss cal16 km frac pace_m pace_s). */
    private void doSportLoop(int type, int secs) throws Exception {
        for (int i = 1; i <= secs; i++) {
            Thread.sleep(1000);
            if (!connected) throw new Exception("link lost during sportloop at " + i + "s");
            int h = i / 3600, m = (i % 3600) / 60, sec = i % 60;
            doWrite(String.format(Locale.ROOT, "fd44%02x01%02x%02x%02x000000000000", type & 0xff, h, m, sec), false);
        }
    }

    private void dumpGatt() {
        if (gatt == null) { Log.w(TAG, "GATT not connected"); return; }
        for (BluetoothGattService s : gatt.getServices()) {
            Log.i(TAG, "GATT svc " + s.getUuid());
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                Log.i(TAG, "GATT   chr " + c.getUuid() + " props=0x" + Integer.toHexString(c.getProperties()));
            }
        }
    }

    private void waitOp(long ms, String what) throws Exception {
        synchronized (opLock) {
            long end = System.currentTimeMillis() + ms;
            while (!opDone && System.currentTimeMillis() < end) opLock.wait(Math.max(1, end - System.currentTimeMillis()));
            if (!opDone) throw new Exception(what + " timeout");
            if (opStatus != 0) Log.w(TAG, what + " status=" + opStatus);
        }
    }

    private void signalOp(int status, byte[] value) {
        synchronized (opLock) { opDone = true; opStatus = status; opValue = value; opLock.notifyAll(); }
    }

    // ------------------------------------------------------------------ callbacks
    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            connected = newState == BluetoothProfile.STATE_CONNECTED;
            Log.i(TAG, "STATE " + (connected ? "connected" : "disconnected") + " status=" + status);
            signalOp(status, null);
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.i(TAG, "SERVICES " + g.getServices().size() + " status=" + status);
            signalOp(status, null);
        }
        @Override public void onMtuChanged(BluetoothGatt g, int m, int status) {
            mtu = m; Log.i(TAG, "MTU " + m + " status=" + status); signalOp(status, null);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            signalOp(status, null);
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            signalOp(status, null);
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            signalOp(status, value);
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) signalOp(status, c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onRx(c, value);
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onRx(c, c.getValue());
        }
        @Override public void onPhyUpdate(BluetoothGatt g, int txPhy, int rxPhy, int status) {
            Log.i(TAG, "PHY tx=" + txPhy + " rx=" + rxPhy + " status=" + status);
        }
        // onConnectionUpdated(gatt, interval, latency, timeout, status) exists but is hidden from the public SDK;
        // without @Override it is still invoked by the framework at runtime, so keep it for the log.
        public void onConnectionUpdated(BluetoothGatt g, int interval, int latency, int timeout, int status) {
            Log.i(TAG, "CONNPARAMS interval=" + interval + " latency=" + latency + " timeout=" + timeout + " status=" + status);
        }
    };

    private void onRx(BluetoothGattCharacteristic c, byte[] value) {
        String hex = bytesToHex(value);
        String chan = c.getUuid().equals(CH_CMD_N) ? "33F2" : c.getUuid().equals(CH_DATA_N) ? "34F2" : c.getUuid().toString().substring(4, 8);
        Log.i(TAG, "RX " + chan + " " + hex);
        synchronized (rxLock) {
            if (rxLog.size() > 5000) { rxLog.subList(0, 2500).clear(); rxMark = Math.max(0, rxMark - 2500); }
            rxLog.add(hex); rxLock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ util
    static byte[] hexToBytes(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
