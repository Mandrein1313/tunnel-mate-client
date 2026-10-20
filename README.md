# Tunnel Mate Client

แอปลูกสำหรับเชื่อมเน็ตผ่าน **SOCKS5 ที่เครื่องหลักแชร์** (Hotspot / Wi‑Fi เดียวกัน)

## วิธีใช้

1. เครื่องหลัก: เปิด Tunnel Mate → เชื่อม VPN → เปิดแชร์ SOCKS5 (+ รหัสผ่านถ้ามี)
2. เครื่องลูก: เชื่อม Wi‑Fi / Hotspot เดียวกับเครื่องหลัก
3. เปิด **Tunnel Mate Client** → กรอก IP:1080 (หรือกดวางจาก Clipboard / รับข้อความแชร์)
4. กด **เชื่อมต่อ** → อนุญาต VPN

## สร้างโปรเจกต์ใน Android Studio

1. New Project → Empty Activity → package `com.tunnelmate.client`
2. วางไฟล์จากโฟลเดอร์นี้ลง `app/src/main/...`
3. คัดลอก native / AAR ของ **hev-socks5-tunnel** (`hev.htproxy.TProxyService`) จากแอปหลักมาใส่ dependency เดียวกัน
4. เพิ่มใน `app/build.gradle`:

```gradle
android {
    namespace "com.tunnelmate.client"
    compileSdk 35
    defaultConfig {
        applicationId "com.tunnelmate.client"
        minSdk 24
        targetSdk 35
    }
}
dependencies {
    implementation "com.google.android.material:material:1.12.0"
    implementation "androidx.appcompat:appcompat:1.7.0"
    implementation "androidx.activity:activity:1.9.0"
    // ใส่ hev / tproxy แบบเดียวกับแอปหลัก
}
```

5. สร้างไอคอน `mipmap/ic_launcher` หรือชี้ชั่วคราวไป `@android:drawable/ic_lock_lock` ใน Manifest

## ไฟล์สำคัญ

| ไฟล์ | หน้าที่ |
|------|---------|
| `ClientMainActivity` | UI + วาง Clipboard + รับ ACTION_SEND |
| `ClientVpnService` | VpnService + เขียน hev-config ชี้เครื่องหลัก |
| `assets/hev-config-client.yml` | template แล้วถูกแทนที่ตอนเชื่อมต่อ |

## หมายเหตุ

- ต้องมี library `hev.htproxy` เหมือนแอปหลัก ไม่งั้น service จะแจ้งว่าไม่มี tun2socks
- เครื่องหลักต้องเปิดแชร์ + VPN อยู่ตลอด
- อย่าใช้ mobile data อย่างเดียวบนเครื่องลูกโดยไม่ได้อยู่วงเดียวกับเครื่องหลัก

## แก้ error: package hev.htproxy does not exist

โปรเจกต์มีคลาส Java `hev.htproxy.TProxyService` แล้ว
**ยังต้องคัดลอกไฟล์ native (.so)** จากแอปหลัก:

1. เปิดโปรเจกต์ **VPN-Manager / Tunnel Mate หลัก**
2. หาโฟลเดอร์ประมาณ:
   - `app/src/main/jniLibs/`
   - หรือ `app/build/intermediates/.../lib/`
   - หรือใน AAR: แตกไฟล์ `.aar` แล้วดู `jni/`
3. คัดลอกไฟล์ที่ชื่อคล้าย:
   - `libhev-socks5-tunnel.so`
   - หรือ `libhev-htproxy.so`
4. วางในแอปลูก:
```
app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so
app/src/main/jniLibs/armeabi-v7a/libhev-socks5-tunnel.so
```
5. Sync + Rebuild

ถ้าชื่อ native method ใน .so ไม่ตรงกับ `nativeStart` / `nativeStop`
ให้เปิดโค้ด `TProxyService` ของแอปหลัก แล้วคัดลอกชื่อเมธอด native มาใส่ใน
`hev/htproxy/TProxyService.java` ของแอปลูกให้ตรงกัน
