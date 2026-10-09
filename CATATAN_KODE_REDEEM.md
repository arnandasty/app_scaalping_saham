# 🎟️ CATATAN KODE REDEEM — Scalping Assistant

> File ini untuk kamu (developer) mengelola kode login user. Semua cukup dari **Firebase Console**, tanpa bikin web admin.

---

## 1. Cara Buat Kode Redeem Baru (2 menit)

### Langkah A — Generate Kode di Laptop
Buka terminal / PowerShell di folder project:

```bash
cd "d:\WEB SAYA\trading-view\app_scalping\tools\redeem"

# Contoh paling sering:
node generate-code.js --days 30 --note "Budi - paket 1 bulan"

# Pilihan lain:
node generate-code.js --days 7 --note "Andi trial 7 hari"
node generate-code.js --until 2026-12-31 --note "Paket sampai akhir tahun"
node generate-code.js --days 30 --count 5 --note "Batch 5 user"
node generate-code.js --days 30 --prefix GO --note "Kode promo GO"
node generate-code.js --days 30 --json   # cuma JSON, buat copy-paste cepat
```

Output akan muncul:
```
KODE  →  SCLPXXXXXXXX
  {
    "active": true,
    "expiresAt": 1767225600000,
    "note": "Budi - paket 1 bulan",
    "createdAt": 1728570000000
  }
```

| Opsi | Arti | Default |
| :--- | :--- | :--- |
| `--days <n>` | Berlaku N hari dari sekarang | `30` |
| `--until YYYY-MM-DD` | Berlaku sampai akhir tanggal itu (timpa `--days`) | - |
| `--note "teks"` | Catatan nama pembeli | kosong |
| `--count <n>` | Bikin banyak sekaligus | `1` |
| `--prefix <teks>` | Ganti awalan kode (harus sama kayak `LoginConfig.CODE_PREFIX`) | `SCLP` |
| `--json` | Cetak JSON doang | off |

### Langkah B — Tempel ke Firebase Console
1. Buka https://console.firebase.google.com → pilih **scalping-assistant**
2. Menu **Firestore Database → tab Data**
3. Klik **+ Start collection** (kalau belum ada) → ID: `redeem_codes`
   Kalau sudah ada, klik koleksi `redeem_codes` → **Add document**
4. **Document ID** = kode tadi (misal `SCLP7K2M9QX4`) — **wajib persis, huruf besar**
5. Tambah 4 field:

   | Field | Type | Value (contoh) |
   | :--- | :--- | :--- |
   | `active` | **boolean** | `true` |
   | `expiresAt` | **int64** | `1794150614011` (dari generator) |
   | `note` | **string** | `Budi - paket 1 bulan` |
   | `createdAt` | **int64** | `1791558614025` |

   > Di Firebase versi baru, `number` namanya **`int64`** (untuk bulat) dan `double` (untuk desimal). Pilih `int64`.

6. Klik **Save**.

**Selesai — kode siap dipakai user.** User buka APK → ketik kode → masuk.

---

## 2. Cara Edit Kode Yang Sudah Ada

Buka **Firestore → Data → klik `redeem_codes` → klik Document ID kodenya** (misal `SCLPPAUCAJ75`).

| Mau apa? | Caranya | Efek ke user |
| :--- | :--- | :--- |
| **Cabut / Blokir** (user langsung logout) | Edit field `active` → `false` → Save | Pas buka app lagi, **auto logout** ke layar login: "Kode sudah dinonaktifkan" |
| **Aktifkan lagi** | `active` → `true` | Bisa login lagi |
| **Perpanjang masa berlaku** | Edit `expiresAt` → isi angka baru → Save | Masa aktif nambah. Hitung angka: buka Console browser (F12) → ketik `new Date("2026-12-31T23:59:59").getTime()` → copy angkanya |
| **Perpendek / Kadaluarsakan** | `expiresAt` → isi angka masa lalu (misal `1700000000000`) | User auto logout: "Kode sudah kadaluarsa pada ..." |
| **Lihat perangkat user** | Lihat field `deviceId` di dokumen (muncul setelah aktivasi pertama, kalau Rules izinkan tulis) | 1 kode = 1 device. Kalau ada `deviceId`, kode tidak bisa dipakai di HP lain |
| **User ganti HP (pindah perangkat)** | **Hapus field `deviceId`** (klik icon tong sampah di field itu) → Save | Kode jadi bebas, bisa diaktivasi di HP baru. Code lain tidak terganggu |
| **Ganti catatan** | Edit `note` | Cuma label buat kamu |

> **Tips epoch:** 1 hari = 86400000 ms. Misal mau nambah 7 hari: `expiresAt_lama + 604800000`.

---

## 3. Contoh Praktis Harian

**User baru beli paket 1 bulan:**
```bash
node generate-code.js --days 30 --note "Rudi - TF Bank 09 Okt"
# → tempel ke Firestore sebagai dokumen baru
```

**User minta perpanjang 30 hari lagi:**
> Buka dokumennya → copy `expiresAt` lama → di Console browser: `new Date("2026-11-08").getTime()` → paste → Save (atau: `expiresAt + 2592000000`)

**User komplain kode dipakai orang lain:**
> Cek `deviceId`. Kalau sudah terisi, artinya kode di-lock ke 1 HP. Jangan hapus `deviceId` kalau memang mau 1 kode = 1 HP.

**Butuh 10 kode reseller:**
```bash
node generate-code.js --days 30 --count 10 --note "Reseller A - batch Okt"
# → akan print 10 kode + 10 JSON, tempel satu-satu
```

---

## 4. Kode Aktif Saat Ini

| Kode | Note | Kadaluarsa | Status |
| :--- | :--- | :--- | :--- |
| `SCLPPAUCAJ75` | Test - paket 1 bulan | 08 Nov 2026 (1794150614011) | `active=true` ✅ |

---

## 5. Kalau User Gagal Login — Arti Pesan

| Pesan di HP | Artinya | Solusi kamu |
| :--- | :--- | :--- |
| "Kode redeem tidak ditemukan." | Salah ketik atau Document ID belum dibikin | Cek huruf besar/kecil, pastikan Document ID = kode |
| "Kode sudah dinonaktifkan." | `active = false` | Ubah jadi `true` kalau mau aktifkan lagi |
| "Kode sudah kadaluarsa pada ..." | `expiresAt` lewat | Edit `expiresAt` ke tanggal baru |
| "Kode ini sudah diaktifkan di perangkat lain." | `deviceId` beda | Hapus `deviceId` kalau user ganti HP & memang boleh |
| "Waktu perangkat terdeteksi mundur..." | User mundurin jam HP | Suruh balikin ke jam otomatis |
| Tidak ada internet | Sesi terakhir dipakai selama belum kadaluarsa | Begitu online, server yang mutusin |

---

## 6. Lokasi Config di Kode

File: `app/src/main/java/com/scalping/assistant/data/auth/LoginConfig.kt`
```kotlin
PROJECT_ID = "scalping-assistant"
APPLICATION_ID = "1:122081106113:android:ad3812dcb92e4c581f1c40"
API_KEY = "AIzaSyBU7LXc9m_s4fjKg97tEt1Fd_D0KaNIK6Y"
COLLECTION_REDEEM_CODES = "redeem_codes"
CODE_PREFIX = "SCLP"
```
> Selama `PROJECT_ID` kosong → **mode pengembangan (bypass login)**. Sekarang sudah terisi, jadi login **aktif**.

---

## 7. Security Rules (sudah terpasang)

Firestore → tab **Rules** → harusnya sudah:
```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /redeem_codes/{code} { allow read: if true; allow write: if false; }
    match /activations/{deviceId} { allow read, write: if false; }
  }
}
```
* `read:true` = HP boleh cek kode (wajib)
* `write:false` = HP **tidak** boleh nulis/ubah kode (kamu ubah dari Console aja) — biar aman meski API Key bocor.

---

**Butuh bantuan?** Buka `SETUP_FIREBASE_LOGIN.md` atau `tools/redeem/README.md`.
