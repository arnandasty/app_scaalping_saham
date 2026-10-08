# 🎟️ Generator Kode Redeem

Alat bantu untuk menerbitkan **kode redeem** login aplikasi Scalping AI Assistant
beserta dokumen Firestore yang siap ditempel. Tidak butuh kredensial apa pun —
hanya Node.js.

> [!NOTE]
> Halaman ini khusus untuk **menerbitkan kode**. Untuk mengaktifkan fitur login
> (membuat project Firebase & mengisi konfigurasi), baca dulu
> [`SETUP_FIREBASE_LOGIN.md`](../../SETUP_FIREBASE_LOGIN.md).

---

## Prasyarat

- Node.js sudah terpasang (`node -v`). Versi apa pun yang modern sudah cukup.

## Cara pakai

Buka terminal di folder `tools/redeem`, lalu:

```bash
# Kode berlaku 30 hari (default)
node generate-code.js

# Kode berlaku 7 hari, dengan catatan nama pembeli
node generate-code.js --days 7 --note "Budi - trial"

# Kode berlaku sampai akhir tanggal tertentu
node generate-code.js --until 2026-12-31 --note "Budi - paket 3 bulan"

# Membuat 5 kode sekaligus
node generate-code.js --days 30 --count 5

# Mengubah awalan kode (default: SCLP)
node generate-code.js --days 30 --prefix GO

# Hanya cetak JSON (untuk ditempel otomatis)
node generate-code.js --days 30 --json
```

| Opsi | Arti | Default |
|------|------|---------|
| `--days <n>` | Masa berlaku N hari dari sekarang | `30` |
| `--until <YYYY-MM-DD>` | Berlaku sampai akhir hari tsb (menimpa `--days`) | — |
| `--note <teks>` | Catatan bebas (nama pembeli, dll.) | kosong |
| `--count <n>` | Jumlah kode sekaligus | `1` |
| `--prefix <teks>` | Awalan kode | `SCLP` |
| `--json` | Cetak hanya JSON | mati |

> [!IMPORTANT]
> Awalan (`--prefix`) **harus sama** dengan `LoginConfig.CODE_PREFIX` di aplikasi.
> Kalau Anda mengubahnya, ubah juga nilai di
> [`LoginConfig.kt`](../../app/src/main/java/com/scalping/assistant/data/auth/LoginConfig.kt#L43).

## Memasukkan kode ke Firebase

1. Buka [Firebase Console](https://console.firebase.google.com) → pilih project.
2. **Firestore Database** → tab **Data**.
3. Koleksi `redeem_codes` (buat lewat **Start collection** bila belum ada).
4. **Add document** → **Document ID** = kode yang dicetak generator
   (mis. `SCLP7K2M9QX4`).
5. Tambahkan field sesuai JSON yang dicetak:
   - `active` → **boolean** → `true`
   - `expiresAt` → **number** → angka epoch millis (mis. `1767225600000`)
   - `note` → **string** → catatan (boleh kosong)
   - `createdAt` → **number**
6. **Save**.

Setelah pengguna mengaktifkan kode di aplikasi, Firestore akan otomatis
menambahkan `deviceId`, `activatedAt`, dan `activationCount` pada dokumen tersebut
(selama Security Rules mengizinkan tulis — lihat catatan di
[`SETUP_FIREBASE_LOGIN.md`](../../SETUP_FIREBASE_LOGIN.md)).

## Mengelola kode yang sudah terbit

| Tindakan | Caranya di Firebase Console |
|----------|-----------------------------|
| **Cabut** (pengguna langsung terlogout) | Ubah field `active` → `false` |
| **Perpanjang** masa berlaku | Ubah `expiresAt` → epoch millis baru |
| **Pindah perangkat** | Hapus field `deviceId` agar bisa diaktifkan ulang |
| **Lihat perangkat aktif** | Lihat nilai `deviceId` di dokumen kode |

> [!TIP]
> Butuh ubah tanggal ke epoch millis? Di browser (DevTools Console):
> `new Date("2026-12-31T23:59:59").getTime()`
