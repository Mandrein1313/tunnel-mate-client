package hev.htproxy;

import android.util.Log;

/**
 * JNI bridge สำหรับ hev-socks5-tunnel (แพ็กเกจมาตรฐาน hev.htproxy)
 *
 * ต้องมี native library:
 *   app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so
 *   app/src/main/jniLibs/armeabi-v7a/libhev-socks5-tunnel.so
 *
 * คัดลอก .so จากแอปหลัก (Tunnel Mate / VPN-Manager)
 * หรือดาวน์โหลด AAR จาก https://github.com/heiher/hev-socks5-tunnel/releases
 */
public final class TProxyService {

    private static final String TAG = "TProxyService";
    private static boolean loaded = false;
    private static String loadError = null;

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
            loaded = true;
            Log.i(TAG, "libhev-socks5-tunnel loaded");
        } catch (UnsatisfiedLinkError e) {
            loadError = e.getMessage();
            Log.e(TAG, "Native library not found: " + loadError);
        }
    }

    private TProxyService() {}

    public static boolean isNativeLoaded() {
        return loaded;
    }

    public static String getLoadError() {
        return loadError;
    }

    /** ชื่อเมธอดต้องตรงกับ JNI ใน hev-socks5-tunnel */
    public static native boolean TProxyStartService(String config_path, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
