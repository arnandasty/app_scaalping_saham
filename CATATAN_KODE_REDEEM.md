# 🎟️ CATATAN KODE REDEEM — Scalping Assistant

> File ini untuk kamu (developer) mengelola kode login user. Semua cukup dari **Firebase Console**, tanpa bikin web admin. Sekarang ada **cara BULK otomatis** biar nggak klik satu-satu.

---

## 1. Cara Buat Kode Redeem Baru

### A. Cara CEPAT — BULK Otomatis via `bulk-push.js` (REKOMENDASI)

**Langsung generate + push ke Firestore, tanpa buka Console satu-satu.**

Buka terminal / PowerShell di folder project:

```bash
cd "d:\WEB SAYA\trading-view\app_scalping\tools\redeem"

# 1 kode 1 bulan
node bulk-push.js --days 30 --note "Budi - paket 1 bulan"

# 3 kode sampai tanggal fix (13 Okt -> 13 Nov) — contoh yang barusan sukses
node bulk-push.js --until 2026-11-13 --count 3 --note "Paket 1 bulan 13 Okt - 13 Nov 2026"

# 10 kode sekaligus
node bulk-push.js --days 30 --count 10 --note "Reseller A batch Okt"

# Trial 7 hari
node bulk-push.js --days 7 --count 5 --note "Trial 7 hari"
```

Output langsung `OK ✅` per kode, dan otomatis muncul di Firestore:
```
  #1 SCLPR5YVZ7P5 ... OK ✅
  #2 SCLPX6R7KFPQ ... OK ✅
  #3 SCLPYXX4G2Z9 ... OK ✅
  Selesai. Cek di Firestore Console → redeem_codes.
```

| Opsi | Arti | Default |
| :--- | :--- | :--- |
| `--days <n>` | Berlaku N hari dari sekarang | `30` |
| `--until YYYY-MM-DD` | Berlaku sampai akhir tanggal itu (timpa `--days`) | - |
| `--note "teks"` | Catatan nama pembeli | kosong |
| `--count <n>` | Jumlah sekaligus | `1` |
| `--prefix <teks>` | Ganti awalan (harus sama `LoginConfig.CODE_PREFIX`) | `SCLP` |

> **Push kode lama yang belum ke Firestore:** `node bulk-push.js --push-existing` (untuk 3 kode SCLPR5YVZ7P5 dkk barusan — sudah sukses ✅)

### B. Cara Generate Saja (Tanpa Push) — `generate-code.js`

Kalau mau lihat dulu sebelum push:

```bash
cd "d:\WEB SAYA\trading-view\app_scalping\tools\redeem"

node generate-code.js --days 30 --note "Budi - paket 1 bulan"
node generate-code.js --until 2026-12-31 --count 5 --note "Batch 5 user"
node generate-code.js --days 30 --json   # cuma JSON
```

Output `KODE → SCLPXXXXXXXX` + JSON. Tempel manual kalau perlu.

### C. Cara Manual via Firebase Console (Cadangan)

Kalau `bulk-push.js` gagal (mis. Rules belum diupdate), tempel manual:

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

---

## 2. Cara Edit Kode Yang Sudah Ada

Buka **Firestore → Data → klik `redeem_codes` → klik Document ID kodenya** (misal `SCLPPAUCAJ75`).

| Mau apa? | Caranya | Efek ke user |
| :--- | :--- | :--- |
| **Cabut / Blokir** (user langsung logout) | Edit field `active` → `false` → Save | Pas buka app lagi, **auto logout** ke layar login: "Kode sudah dinonaktifkan" |
| **Aktifkan lagi** | `active` → `true` | Bisa login lagi |
| **Perpanjang masa berlaku** | Edit `expiresAt` → isi angka baru → Save | Masa aktif nambah. Hitung angka: buka Console browser (F12) → ketik `new Date("2026-11-13T23:59:59").getTime()` → copy angkanya |
| **Perpendek / Kadaluarsakan** | `expiresAt` → isi angka masa lalu (misal `1700000000000`) | User auto logout: "Kode sudah kadaluarsa pada ..." |
| **Lihat perangkat user** | Lihat field `deviceId` di dokumen (muncul setelah aktivasi pertama) | 1 kode = 1 device. Kalau ada `deviceId`, kode tidak bisa dipakai di HP lain |
| **User ganti HP (pindah perangkat)** | **Hapus field `deviceId`** (klik icon tong sampah di field itu) → Save | Kode jadi bebas, bisa diaktivasi di HP baru. Code lain tidak terganggu |
| **Ganti catatan** | Edit `note` | Cuma label buat kamu |

> **Tips epoch:** 1 hari = 86400000 ms. Misal mau nambah 7 hari: `expiresAt_lama + 604800000`.

**Alternatif via terminal (bulk perpanjang):** belum ada — edit `expiresAt` paling cepat lewat Console. Untuk bikin kode baru, selalu pakai `bulk-push.js`.

---

## 3. Contoh Praktis Harian

**User baru beli paket 1 bulan (langsung push):**
```bash
node bulk-push.js --days 30 --note "Rudi - TF Bank 09 Okt"
# → langsung OK ✅, tidak perlu buka Console
```

**3 user baru paket 13 Okt - 13 Nov (yang barusan sukses):**
```bash
node bulk-push.js --until 2026-11-13 --count 3 --note "Paket 1 bulan 13 Okt - 13 Nov 2026"
# → SCLPR5YVZ7P5, SCLPX6R7KFPQ, SCLPYXX4G2Z9 langsung terpush ✅
```

**User minta perpanjang 30 hari lagi:**
> Buka dokumennya → copy `expiresAt` lama → di Console browser: `new Date("2026-12-13").getTime()` → paste → Save (atau: `expiresAt + 2592000000`)

**Butuh 10 kode reseller (langsung push):**
```bash
node bulk-push.js --days 30 --count 10 --note "Reseller A - batch Okt"
```

---

## 4. Kode Aktif Saat Ini

| Kode | Note | Kadaluarsa | Status |
| :--- | :--- | :--- | :--- |
| `SCLPPAUCAJ75` | Test - paket 1 bulan | 08 Nov 2026 (1794150614011) | `active=true` ✅ |
| `SCLPR5YVZ7P5` | Paket 1 bulan 13 Okt - 13 Nov 2026 | 13 Nov 2026 23:59 (1794589199999) | `active=true` ✅ via bulk-push |
| `SCLPX6R7KFPQ` | Paket 1 bulan 13 Okt - 13 Nov 2026 | 13 Nov 2026 23:59 (1794589199999) | `active=true` ✅ via bulk-push |
| `SCLPYXX4G2Z9` | Paket 1 bulan 13 Okt - 13 Nov 2026 | 13 Nov 2026 23:59 (1794589199999) | `active=true` ✅ via bulk-push |

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

## 7. Security Rules (sudah terpasang — versi BULK + Auto Lock)

Firestore → tab **Rules** → harusnya sudah (yang bikin bulk-push bisa):

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /redeem_codes/{code} {
      allow read: if true;
      allow create: if request.resource.data.keys().hasAll(['active','expiresAt','note','createdAt']);
      allow update: if request.resource.data.diff(resource.data).affectedKeys().hasOnly(['deviceId','activatedAt','activationCount']);
    }
    match /activations/{deviceId} {
      allow read, write: if true;
    }
  }
}
```
* `read:true` = HP boleh cek kode (wajib)
* `create: hasAll(...)` = boleh **bikin kode baru** via `bulk-push.js` (wajib ada 4 field), tapi **tidak bisa** sembarang nulis
* `update: hasOnly(deviceId...)` = setelah jadi, cuma boleh nge-lock device (1 kode = 1 HP otomatis) — **tidak bisa** ubah `active`/`expiresAt` dari HP
* Kalau mau balik ke mode manual (blokir total write): ganti `allow create/update` jadi `allow write: if false;`

---

## 8. File Tools

| File | Fungsi |
| :--- | :--- |
| `tools/redeem/generate-code.js` | Generate saja (preview JSON) |
| `tools/redeem/bulk-push.js` | **Generate + langsung push ke Firestore (efisien)** |
| `tools/redeem/README.md` | Dokumentasi asli |

**Butuh bantuan?** Buka `SETUP_FIREBASE_LOGIN.md` atau `tools/redeem/README.md`.
