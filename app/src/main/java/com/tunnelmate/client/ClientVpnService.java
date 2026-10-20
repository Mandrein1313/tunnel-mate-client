package com.tunnelmate.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import hev.htproxy.TProxyService;

/**
 * VPN client → remote SOCKS5 (เครื่องหลักที่แชร์)
 * ใช้ hev-socks5-tunnel (TProxyService) เหมือนแอปหลัก
 * ถ้าไม่มี native library จะเชื่อมไม่สำเร็จและแจ้งใน notification
 */
public class ClientVpnService extends VpnService {

    private static final String TAG = "ClientVpnService";
    public static final String ACTION_START = "CLIENT_START";
    public static final String ACTION_STOP = "CLIENT_STOP";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASS = "pass";

    private static final String CH_ID = "client_vpn";
    private static final int NOTIF_ID = 42;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private ParcelFileDescriptor tunFd;
    private File configFile;
    private boolean tproxyStarted = false;

    public static boolean isRunning() {
        return running.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopClient();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, 1080);
            String user = intent.getStringExtra(EXTRA_USER);
            String pass = intent.getStringExtra(EXTRA_PASS);
            startForeground(NOTIF_ID, buildNotification("กำลังเชื่อมต่อ..."));
            new Thread(() -> startClient(host, port, user, pass), "client-vpn-start").start();
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startClient(String host, int port, String user, String pass) {
        try {
            if (host == null || host.trim().isEmpty()) {
                fail("ไม่มีโฮสต์");
                return;
            }
            host = host.trim();
            if (port <= 0 || port > 65535) port = 1080;

            // หยุดของเก่า
            stopTunnelOnly();

            Builder builder = new Builder()
                    .setSession("Tunnel Mate Client")
                    .setMtu(1280)
                    .addAddress("10.0.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8");

            // กัน loop: ไม่ส่ง traffic ของแอปตัวเองเข้า TUN (optional)
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false);
            }

            tunFd = builder.establish();
            if (tunFd == null) {
                fail("ไม่สามารถสร้าง VPN interface");
                return;
            }

            writeConfig(host, port, user, pass);

            if (!TProxyService.isNativeLoaded()) {
                fail("ยังไม่มีไฟล์ .so ของ hev-socks5-tunnel\n"
                        + "คัดลอกจากแอปหลักไปที่\n"
                        + "app/src/main/jniLibs/arm64-v8a/\n"
                        + (TProxyService.getLoadError() != null
                           ? TProxyService.getLoadError() : ""));
                return;
            }

            boolean ok = false;
            try {
                ok = TProxyService.TProxyStartService(
                        configFile.getAbsolutePath(),
                        tunFd.getFd()
                );
            } catch (Throwable t) {
                Log.e(TAG, "TProxyStartService: " + t.getMessage(), t);
                fail("เริ่ม tun2socks ไม่สำเร็จ: " + t.getMessage());
                return;
            }

            if (!ok) {
                fail("เริ่ม tun2socks ไม่สำเร็จ");
                return;
            }

            tproxyStarted = true;
            running.set(true);
            updateNotification("เชื่อมแล้ว → " + host + ":" + port);
            Log.i(TAG, "Client connected via SOCKS5 " + host + ":" + port);
        } catch (Exception e) {
            Log.e(TAG, "startClient", e);
            fail(e.getMessage());
        }
    }

    private void writeConfig(String host, int port, String user, String pass) throws Exception {
        // อ่าน template แล้วแทนที่
        String base;
        try (InputStream in = getAssets().open("hev-config-client.yml")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            base = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
        }

        StringBuilder socks = new StringBuilder();
        socks.append("socks5:\n");
        socks.append("  port: ").append(port).append("\n");
        socks.append("  address: ").append(host).append("\n");
        socks.append("  udp: 'udp'\n");
        if (user != null && !user.isEmpty()) {
            socks.append("  username: '").append(user.replace("'", "")).append("'\n");
            socks.append("  password: '").append(pass != null ? pass.replace("'", "") : "").append("'\n");
        }

        // แทนที่บล็อก socks5 ใน template แบบง่าย
        String out = base.replaceAll(
                "(?s)socks5:.*?\\n(?=misc:)",
                socks.toString() + "\n"
        );
        if (out.equals(base)) {
            // fallback เขียนทั้งไฟล์
            out = "tunnel:\n  mtu: 1280\n  ipv4: 10.0.0.2\n  ipv6: '::'\n\n"
                    + socks
                    + "\nmisc:\n  log-file: stderr\n  log-level: warn\n"
                    + "  task-stack-size: 65536\n  connect-timeout: 15000\n"
                    + "  read-write-timeout: 300000\n";
        }

        configFile = new File(getFilesDir(), "hev-config-client.yml");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(out.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "Config written:\n" + out);
    }

    private void fail(String msg) {
        updateNotification("ล้มเหลว: " + msg);
        stopClient();
    }

    private void stopTunnelOnly() {
        if (tproxyStarted) {
            try {
                TProxyService.TProxyStopService();
            } catch (Throwable ignored) {}
            tproxyStarted = false;
        }
        if (tunFd != null) {
            try { tunFd.close(); } catch (Exception ignored) {}
            tunFd = null;
        }
    }

    private void stopClient() {
        running.set(false);
        stopTunnelOnly();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopTunnelOnly();
        running.set(false);
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopClient();
        super.onRevoke();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Tunnel Mate Client", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, ClientMainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, ClientVpnService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("Tunnel Mate Client")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(0, "หยุด", stopPi)
                .build();
    }
}
