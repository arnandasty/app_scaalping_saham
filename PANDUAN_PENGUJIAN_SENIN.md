# 📋 Panduan & Checklist Pengujian Live Market (Senin, 09:00 WIB)

Dokumen ini adalah panduan pengujian resmi saat Bursa Efek Indonesia (BEI) dibuka pada **Senin pukul 09:00 WIB**, untuk memvalidasi performa aliran data transaksi berjalan (*Running Trade Tick-by-Tick*), deteksi akumulasi bandar, serta proteksi Anti-Pucuk secara *real-time*.

---

## ⏰ Jadwal & Tahapan Pengujian

| Waktu (WIB) | Fase Pengujian | Target yang Diamati |
| :--- | :--- | :--- |
| **08:50 - 08:59** | **Pra-Market (Persiapan)** | Pastikan akun Stockbit login, koneksi internet stabil, aplikasi dibuka. |
| **09:00 - 09:05** | **Market Opening Spike** | Banjir transaksi WebSocket pertama, inisialisasi Running Trade & Movers. |
| **09:05 - 09:30** | **Live Scalping Testing** | Pengujian sinyal *Early Momentum*, *Anti-Pucuk*, dan *Pullback Sehat*. |

---

## 🎯 4 Skenario Pengujian Utama di Layar HP

### 1. Uji Aliran Data Mentah (WebSocket Stream)
* **Kondisi:** Pukul 09:00:00 WIB pasar resmi dibuka.
* **Langkah:**
  1. Perhatikan bilah teks log di bawah layar aplikasi.
  2. Saat transaksi bursa membanjir, teks status akan berkedip aktif dengan kode:
     `🎯 [WS_MSG] wss-trading.stockbit.com/ws` atau `🎯 [FETCH_DATA] marketdetectors/...`
  3. **Ketuk (*tap*) teks status tersebut** untuk membuka popup *Log Intercept*.
  4. Pastikan transaksi per detik masuk tanpa hambatan.

### 2. Uji Saham "Akan Naik" (Early Momentum)
* **Kondisi:** Saham yang baru mulai bergerak naik di rentang **+0.5% s/d +5.0%** dengan lonjakan volume awal.
* **Yang Harus Muncul di Layar:**
  - Lencana bandar berwarna hijau: `🟢 Big Acc (Rp xxx)` atau `🟢 Acc`.
  - Di bawah nama saham tertera: `🎯 [Early Momentum (Akan Naik)]`.
  - Rekomendasi: `BUY` atau `STRONG BUY` dengan skor tinggi ($\ge 75$).

### 3. Uji Filter Anti-Pucuk vs Super Momentum (Kereta Laju)
* **Kasus A: Pucuk Lelah (Anti-Pucuk Normal):**
  - Saham melesat $\ge +7.0\%$ tetapi HAKA sepi, bandar netral/distribusi, atau tanpa tembok bid tebal.
  - Sistem **TIDAK BOLEH** memberikan `BUY` $\rightarrow$ Terkunci keras pada **`WATCH`** (*"Dilarang beli di pucuk — tunggu pullback ke support"*).
* **Kasus B: Super Momentum (Kereta Laju Menuju ARA):**
  - Saham melesat $\ge +7.0\%$ dan nangkring di High, TETAPI terbukti **HAKA Masif ($\ge 2\times$ HAKI)**, bandar **`Big Acc` / `Acc`**, dan ada tembok penahan bid tebal.
  - Sistem **MEMBUKA KUNCI** dan memberikan rekomendasi: **`BUY`** atau **`STRONG BUY`**!
  - Gaya trading: `🚀 Super Momentum (Ride the Wave)`.
  - Stop Loss otomatis disetel sangat ketat: **1 s/d 2 tik di bawah entry** (maksimal -1.5%).

### 4. Uji Analisis Pullback: Sehat vs Guyuran Bandar
* **Kondisi:** Saham yang tadi naik tinggi lalu terkoreksi turun $1.5\%$ s/d $5.5\%$ dari harga pucuknya.
* **Dua Kemungkinan Skenario:**
  - **Skenario A (Guyuran / Jebakan Pucuk):**
    - Bandar terdeteksi `🔴 Big Dist` atau `🔴 Dist` (HAKI masif).
    - Status langsung jatuh ke **`AVOID`** dengan peringatan: *"BAHAYA: Terdeteksi Distribusi Bandar pada saat harga turun."* $\rightarrow$ Menyelamatkan Anda dari risiko nyangkut di pucuk!
  - **Skenario B (Pullback Sehat / Bantalan Support):**
    - Bandar tetap `🟢 Big Acc` atau `🟢 Acc` (bandar tidak jualan saat ritel panik).
    - Harga tertahan di atas *Support* atau *Bid Wall*.
    - Rekomendasi berubah menjadi **`BUY`** dengan label `🎯 [Buy on Pullback (Bantalan Support)]`.

### 5. Uji Detail Panel Emiten (Bottom Sheet)
* **Langkah:** Ketuk salah satu kartu saham yang menarik perhatian Anda.
* **Periksa Kotak "🕵️ Bandar Detector (Live Stream)":**
  - **Status:** Menampilkan akumulasi/distribusi secara jelas.
  - **Avg Price Bandar:** Memperlihatkan estimasi harga modal bandar hari ini.
  - **Total Nilai:** Menampilkan jumlah uang yang diputar bandar (dalam Juta / Miliar / Triliun Rupiah).
  - **Catatan Analisis:** Memberitahu apakah harga sekarang berada di bawah modal bandar (peluang aman) atau sudah terlalu jauh di atas rata-rata modal bandar.

---

## 🛠️ Tindakan Jika Mengalami Kendala

1. **Jika Layar Web Atas Meminta Login:**
   - Cukup login kembali akun Stockbit Anda di panel browser atas, sesi akan otomatis diperbarui ke seluruh sistem.
2. **Jika Ingin Mengubah Pembagian Layar:**
   - Ketuk garis pemisah tengah (*Drag Handle*) untuk beralih mode tampilan (Mode Stockbit Lebih Besar vs Mode AI Panel Lebih Besar).
3. **Jika Ingin Mencatat Simulasi Trade:**
   - Buka lembar detail saham $\rightarrow$ Masukkan lot $\rightarrow$ Tekan tombol hijau **"DONE BUY — Catat ke Portfolio"** untuk melacak profit/loss secara otomatis.
