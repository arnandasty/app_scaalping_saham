# Scalping Assistant AI - Development Progress Report

Dokumen ini berisi catatan lengkap dan mendetail mengenai seluruh tahapan pengembangan aplikasi **Scalping Assistant AI**, mulai dari konsep awal, arsitektur data pipeline, hingga penyelesaian bug tahap akhir.

---

## 1. Arsitektur & Infrastruktur Dasar
* **Platform:** Android Native (Kotlin).
* **Konsep:** Aplikasi asisten *scalping* saham Indonesia (IDX) berbasis analisis Orderbook (Bid/Offer Tape Reading), Indikator Teknikal Intraday, dan Konteks Sesi Bursa secara *real-time*.
* **Multi-Stream Scraping Engine:**
  * Menggunakan sistem **Multi-WebView Paralel** untuk menarik data tanpa bentrok:
    1. `webViewStockbit` (Utama / Visible): Menampilkan antrean Orderbook langsung yang dipilih manual oleh pengguna (`stockbit.com/orderbook`).
    2. `webViewMovers` (Latar / Alpha 0): Menyedot saham teraktif berdasarkan nilai transaksi (`stockbit.com/market/hot` - Top Value).
    3. `webViewFreq` (Latar / Alpha 0): Menyedot saham dengan frekuensi transaksi terbanyak (`stockbit.com/market/freq` - Top Frequency, favorit scalper).
    4. `webViewVolume` (Latar / Alpha 0): Menyedot saham dengan akumulasi perpindahan lot terbanyak (`stockbit.com/market/volume` - Top Volume).
  * **Headless Rendering Trick:** WebView latar diberi dimensi desktop (1280x720) dengan `alpha="0"` dan ditaruh di belakang layar utama agar Chromium/GPU tidak menonaktifkan rendering elemen React Virtual DOM.
  * **Javascript Interface Bridge:** Menggunakan `StockbitBridge` untuk tab Manual dan `MoversBridge` untuk tab Movers yang mengeksekusi script injeksi (`stockbit_injector.js`) secara asinkron.

---

## 2. Mesin Analisis (Engines)
Aplikasi ini ditenagai oleh tiga mesin utama yang berjalan secara asinkron:

### A. OrderFlowAnalyzer.kt (Analisis Bandar & Tape Reading)
* **Value-Based Analysis:** Menghitung ketebalan tembok bid/offer bukan dari jumlah Lot statis, melainkan dinormalisasi ke nilai Rupiah riil (`Volume x Harga x 100`).
* **Deteksi Tembok:** 
  * Tembok *Offer* (Resisten Bandar): Minimal Rp 200 Juta.
  * Tembok *Bid* (Bantalan Support): Minimal Rp 500 Juta.
* **Deteksi Manipulasi:** Mengidentifikasi *Fake Wall* (Tembok palsu) dan *Absorption* (Bandar menampung guyuran ritel).
* **Breakout Logic:** Menangkap momen ketika tembok offer raksasa berhasil dihancurkan oleh rentetan *Aggressive Buy* (HAKA masif).

### B. TechnicalAnalyzer.kt (Analisis Teknikal)
* **Data Feed:** Terintegrasi dengan Yahoo Finance API (`YahooFinanceRepository`) untuk menarik data *intraday candles*. Menggunakan sistem *caching* (5 menit untuk candles) untuk efisiensi kuota dan latensi.
* **Indikator Intraday:** Menghitung VWAP, MFI (Money Flow Index), EMA (9 & 21), Bollinger Bands (Multipliers), MACD (Histogram), dan Supertrend Bullish/Bearish.
* **Fibonacci & S/R:** Menghitung level Fibonacci *Retracement* secara dinamis berdasarkan 50 *candle* terakhir.
* **Tick Rounding (Fraksi Harga BEI):** Mengimplementasikan fungsi pembulatan fraksi harga bursa (`PriceFraction.kt`) agar nilai *Support* dan *Resistance* selalu valid dengan fraksi harga Bursa Efek Indonesia (kelipatan Rp 1, 2, 5, 10, dan 25).

### C. ScoringEngine.kt (Sistem Penilaian & Sinyal)
* **Scoring System (0-100):** Menggabungkan bobot OrderFlow (0-35), Teknikal (0-35), dan Konteks Sesi Pasar (0-20), disesuaikan dengan penalti Risk/Reward dan proteksi Overbought (> +15%).
* **Rekomendasi (Hysteresis):** Memberikan sinyal STRONG BUY, BUY, WATCH, dan AVOID. Dilengkapi dengan logika *Anti-Flicker* (Hysteresis) agar sinyal tidak berkedip-kedip saat skor berada di batas ambang pergantian.
* **Trading Plan Real-Time:** 
  * **Entry Price:** Selalu mengikuti harga *real-time* saat sinyal muncul.
  * **Target Price:** Menggunakan batas tembok *offer* terdekat atau resisten teknikal terdekat.
  * **Stop Loss (SL):** Berlindung 1 *tick* di bawah tembok *bid* raksasa (> Rp 500 Juta) atau di titik *support* teknikal.
  * **Gaya Trading Dinamis:** Otomatis menentukan gaya *Buy on Breakout (Tembus Offer)*, *Buy on Weakness*, *Buy on Support*, atau *Momentum*.
* **Panic Bailout:** Fitur darurat yang otomatis membunyikan alarm dan getaran berulang jika mendeteksi guyuran transaksi jual > Rp 500 Juta setelah rekomendasi beli diberikan.

---

## 3. User Interface (UI)
* **3-Tab Separation:**
  * **Tab Manual:** Menampilkan khusus saham yang dipantau atau diganti secara manual oleh pengguna di WebView orderbook atas.
  * **Tab Movers:** Menampilkan gabungan saham teraktif dari 3 kategori market: Top Value, Top Frequency, dan Top Volume secara otomatis tanpa duplikasi.
  * **Tab Top Picks:** Saham-saham dengan filter ketat (Skor AI ≥ 75 dan Risk/Reward Ratio ≥ 1.5) dari gabungan Manual dan Movers.
* **Detail Bottom Sheet:** Menampilkan rincian indikator teknikal (EMA, BB, MACD, MFI, Supertrend), rincian analisa bandar (Delta volume, Fake wall, Akumulasi), Trading Plan terperinci, peringatan risiko, serta level S/R.
* **Active Trade Tracking Banner:** Banner hijau/merah di atas untuk melacak PnL saham aktif yang sedang ditradingkan secara live.

---

## 4. Daftar Bug Kritis yang Telah Diselesaikan (Bug Fixes)

1. **Movers Panel Blank & GPU Off-screen Drop:**
   - *Penyebab:* Saat WebView diset `width/height = 0dp` atau dibuang keluar layar (`-10000dp`), GPU Android tidak merender halaman tersebut sehingga React Virtual DOM di Stockbit membatalkan rendering komponen sidebar.
   - *Solusi:* Mengembalikan ukuran menjadi 1280x720 (desktop viewport) dengan transparansi `alpha="0"` di lapisan belakang layout utama.

2. **Sinkronisasi Sesi Login Nyangkut:**
   - *Penyebab:* WebView latar belakang terkunci di URL `/login` meskipun pengguna sudah login di WebView utama.
   - *Solusi:* Menambahkan auto-redirect pada event `onPageFinished` WebView utama yang mendeteksi status login dan memerintahkan WebView latar untuk segera memuat halaman target.

3. **Skor Terus Bergoyang Saat Market Tutup (DOM Lot Jitter):**
   - *Penyebab:* Meskipun bursa tutup, pembaruan DOM mikro pada tabel antrean menghasilkan selisih lot kecil yang disalahartikan sebagai transaksi agresif (Delta Volume).
   - *Solusi:* Memasang filter sesi pasar pada `OrderBookRepository`. Saat market dalam status `CLOSED`, `PRE_OPEN`, atau `BREAK`, sistem mengabaikan fluktuasi lot dan hanya merekam perubahan jika terjadi pergeseran harga riil.

4. **Berkurangnya Jumlah Saham Terbaca dari 12 Menjadi 11 Saat Ganti Emiten:**
   - *Penyebab:* Script `stockbit_injector.js` memiliki aturan ketat `validTickersInside === 1`. Saat pengguna mengganti ticker di kotak pencarian, React menyisakan elemen input autocomplete bayangan sehingga jumlah input menjadi 2 dan kontainer tersebut dibuang/di-skip.
   - *Solusi:* Mengubah logika menjadi validasi jumlah ticker unik (`uniqueCount === 1`), sehingga kontainer tetap sah diproses meskipun terdapat input duplikat di dalamnya.

5. **Saham Manual Tersedot / Pindah ke Tab Movers:**
   - *Penyebab:* Pipa data sebelumnya hanya ada 1 dan memisahkan tab berdasarkan perbandingan array `topTickers` yang datanya bercampur.
   - *Solusi:* Memisahkan pipeline data menjadi 2 fungsi terisolasi: `processJsonData()` khusus untuk `_manualFlow`, dan `processMoversJsonData()` khusus untuk `_moversFlow`.

6. **Dukungan Penuh Top Frequency & Top Volume:**
   - *Kebutuhan:* Scalper membutuhkan saham dengan frekuensi transaksi tinggi dan likuiditas melimpah, bukan hanya Top Gainer.
   - *Solusi:* Mengintegrasikan `webViewFreq` (`market/freq`) dan `webViewVolume` (`market/volume`) ke dalam pipeline `moversFlow` secara paralel dengan sistem deduplikasi.

7. **Stale Technical Cache (Bug Support/Resisten Ngawur):**
   - *Penyebab:* Cache perhitungan teknikal sebelumnya bersifat permanen, mengunci harga pertama dan memicu anomali nilai Support/Resisten (misal S1 Rp 126 pada saham harga 900-an).
   - *Solusi:* Mengunci cache hanya untuk candles Yahoo Finance, sementara perhitungan S/R dan indikator selalu dihitung ulang menggunakan `lastPrice` terbaru secara real-time.

8. **Fractional Tick Error (S/R Tidak Sesuai Fraksi BEI):**
   - *Penyebab:* Perhitungan matematis Fibonacci menghasilkan angka desimal yang tidak ada di fraksi bursa (misal 20.262).
   - *Solusi:* Mengintegrasikan `PriceFraction.roundToValidTick` ke seluruh level Support, Resisten, Target, dan Stop Loss.

---

## 5. Rencana & Pengujian Selanjutnya (Next Steps)
* **Live Paper Trading (2 - 3 Hari):** Menguji sinyal STRONG BUY dan BUY selama jam operasional bursa (09.00 - 16.00 WIB) tanpa eksekusi dana riil untuk memvalidasi akurasi Tape Reading.
* **Evaluasi Kecepatan Injeksi:** Mengamati konsumsi baterai dan kestabilan 4 WebView paralel saat pasar ramai transaksi.
* **Kalibrasi Penalti Overbought:** Memastikan saham yang mengalami kenaikan tajam (> 15%) namun didukung volume masif tetap diberikan sinyal rasional tanpa terjebak FOMO.

---
*Dokumen ini diperbarui secara otomatis dan mencakup seluruh perombakan pipeline terbaru.*
