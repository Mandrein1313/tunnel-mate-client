package com.tunnelmate.client;

import android.content.Context;
import android.content.SharedPreferences;

public class ClientPrefs {
    private final SharedPreferences sp;

    public ClientPrefs(Context ctx) {
        sp = ctx.getApplicationContext()
                .getSharedPreferences("client_prefs", Context.MODE_PRIVATE);
    }

    public String getHost() { return sp.getString("host", ""); }
    public void setHost(String v) { sp.edit().putString("host", v == null ? "" : v.trim()).apply(); }

    public int getPort() {
        try { return Integer.parseInt(sp.getString("port", "1080")); }
        catch (Exception e) { return 1080; }
    }
    public void setPort(int p) { sp.edit().putString("port", String.valueOf(p)).apply(); }

    public String getUser() { return sp.getString("user", ""); }
    public void setUser(String v) { sp.edit().putString("user", v == null ? "" : v.trim()).apply(); }

    public String getPass() { return sp.getString("pass", ""); }
    public void setPass(String v) { sp.edit().putString("pass", v == null ? "" : v).apply(); }
}
