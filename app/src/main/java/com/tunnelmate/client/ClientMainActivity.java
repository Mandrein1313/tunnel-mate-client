package com.tunnelmate.client;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientMainActivity extends AppCompatActivity {

    private ClientPrefs prefs;
    private TextInputEditText edtHost, edtPort, edtUser, edtPass;
    private MaterialButton btnConnect, btnPaste;
    private TextView txtStatus;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnService();
                } else {
                    setStatus("ไม่ได้อนุญาต VPN", false);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_main);

        prefs = new ClientPrefs(this);
        edtHost = findViewById(R.id.edtHost);
        edtPort = findViewById(R.id.edtPort);
        edtUser = findViewById(R.id.edtUser);
        edtPass = findViewById(R.id.edtPass);
        btnConnect = findViewById(R.id.btnConnect);
        btnPaste = findViewById(R.id.btnPaste);
        txtStatus = findViewById(R.id.txtStatus);

        edtHost.setText(prefs.getHost());
        edtPort.setText(String.valueOf(prefs.getPort()));
        edtUser.setText(prefs.getUser());
        edtPass.setText(prefs.getPass());

        btnConnect.setOnClickListener(v -> toggleConnect());
        btnPaste.setOnClickListener(v -> pasteFromClipboard());

        // รับข้อความแชร์จากแอปอื่น
        handleSendIntent(getIntent());
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSendIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (ClientVpnService.isRunning()) {
            setStatus("● เชื่อมต่อแล้ว", true);
            btnConnect.setText("ตัดการเชื่อมต่อ");
        } else {
            setStatus("● ยังไม่เชื่อมต่อ", false);
            btnConnect.setText("เชื่อมต่อ");
        }
    }

    private void setStatus(String text, boolean connected) {
        if (txtStatus == null) return;
        txtStatus.setText(text);
        txtStatus.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.green : R.color.gray));
    }

    private void toggleConnect() {
        if (ClientVpnService.isRunning()) {
            Intent i = new Intent(this, ClientVpnService.class);
            i.setAction(ClientVpnService.ACTION_STOP);
            startService(i);
            setStatus("● หยุดแล้ว", false);
            btnConnect.setText("เชื่อมต่อ");
            return;
        }
        saveForm();
        String host = textOf(edtHost);
        if (host.isEmpty()) {
            setStatus("กรุณาใส่ IP เครื่องหลัก", false);
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        saveForm();
        Intent i = new Intent(this, ClientVpnService.class);
        i.setAction(ClientVpnService.ACTION_START);
        i.putExtra(ClientVpnService.EXTRA_HOST, textOf(edtHost));
        i.putExtra(ClientVpnService.EXTRA_PORT, parsePort());
        i.putExtra(ClientVpnService.EXTRA_USER, textOf(edtUser));
        i.putExtra(ClientVpnService.EXTRA_PASS, textOf(edtPass));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        setStatus("● กำลังเชื่อมต่อ...", false);
        btnConnect.setText("ตัดการเชื่อมต่อ");
        btnConnect.postDelayed(this::refreshStatus, 2000);
    }

    private void saveForm() {
        prefs.setHost(textOf(edtHost));
        prefs.setPort(parsePort());
        prefs.setUser(textOf(edtUser));
        prefs.setPass(textOf(edtPass));
    }

    private int parsePort() {
        try {
            return Integer.parseInt(textOf(edtPort));
        } catch (Exception e) {
            return 1080;
        }
    }

    private static String textOf(TextInputEditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || cm.getPrimaryClip() == null) return;
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        if (item == null) return;
        CharSequence t = item.coerceToText(this);
        if (t != null) applyConfigText(t.toString());
    }

    private void handleSendIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && intent.getType() != null
                && intent.getType().startsWith("text/")) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) applyConfigText(shared);
        }
    }

    /**
     * รองรับข้อความจากแอปหลัก เช่น:
     * โฮสต์: 10.158.76.80
     * พอร์ต: 1080
     * Username: vpnshare
     * Password: xxx
     * หรือ socks5://user:pass@ip:port
     */
    private void applyConfigText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String text = raw.trim();

        // socks5://user:pass@host:port
        Pattern uri = Pattern.compile(
                "socks5://(?:([^:@/]+):([^@/]*)@)?([^:/\\s]+):(\\d+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = uri.matcher(text);
        if (m.find()) {
            if (m.group(1) != null) edtUser.setText(m.group(1));
            if (m.group(2) != null) edtPass.setText(m.group(2));
            edtHost.setText(m.group(3));
            edtPort.setText(m.group(4));
            saveForm();
            setStatus("นำเข้าจาก URI แล้ว", false);
            return;
        }

        // โฮสต์: x.x.x.x
        Matcher host = Pattern.compile(
                "(?:โฮสต์|host|ip)\\s*[:：]\\s*([0-9.]+)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (host.find()) edtHost.setText(host.group(1));

        Matcher port = Pattern.compile(
                "(?:พอร์ต|port)\\s*[:：]\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (port.find()) edtPort.setText(port.group(1));

        Matcher user = Pattern.compile(
                "(?:username|user)\\s*[:：]\\s*(\\S+)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (user.find()) edtUser.setText(user.group(1));

        Matcher pass = Pattern.compile(
                "(?:password|pass)\\s*[:：]\\s*(\\S+)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (pass.find()) edtPass.setText(pass.group(1));

        // IP:port ล้วน ๆ
        Matcher ipPort = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})\\b")
                .matcher(text);
        if (ipPort.find() && textOf(edtHost).isEmpty()) {
            edtHost.setText(ipPort.group(1));
            edtPort.setText(ipPort.group(2));
        }

        saveForm();
        setStatus("นำเข้าข้อความแล้ว", false);
    }
}
