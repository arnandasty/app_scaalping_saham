# Scalping Assistant AI - Development Progress Report

Dokumen ini berisi catatan lengkap dan mendetail mengenai seluruh tahapan pengembangan aplikasi **Scalping Assistant AI**, mulai dari konsep awal hingga penyelesaian bug tahap akhir.

## 1. Arsitektur & Infrastruktur Dasar
* **Platform:** Android (Kotlin).
* **Konsep:** Aplikasi pendamping *scalping* saham Indonesia (IDX) yang menganalisis Orderbook (Bid/Offer) dan indikator teknikal secara *real-time*.
* **Data Source & Scraping:**
  * Menggunakan `WebView` tersembunyi yang menginjeksi script JavaScript (`stockbit_injector.js`) untuk mengekstrak data orderbook secara langsung dari web Stockbit.
  * Menggunakan `Android User-Agent` untuk orderbook dan `Desktop User-Agent` untuk melakukan ekstraksi pada panel "Movers".
  * Komunikasi *real-time* dari JavaScript ke Kotlin menggunakan `JavascriptInterface` (`WebAppInterface`).

## 2. Mesin Analisis (Engines)
Aplikasi ini ditenagai oleh tiga mesin utama yang berjalan secara asinkron:

### A. OrderFlowAnalyzer.kt (Analisis Bandar & Tape Reading)
* **Value-Based Analysis:** Menghitung ketebalan tembok bid/offer bukan dari jumlah Lot statis, melainkan dinormalisasi ke nilai Rupiah (Volume x Harga x 100).
* **Deteksi Tembok:** 
  * Tembok *Offer* (Resisten Bandar): Minimal Rp 200 Juta.
  * Tembok *Bid* (Bantalan Support): Minimal Rp 500 Juta.
* **Deteksi Manipulasi:** Mengidentifikasi *Fake Wall* (Tembok palsu) dan *Absorption* (Bandar menampung guyuran ritel).
* **Breakout Logic:** Menangkap momen ketika tembok offer raksasa berhasil dihancurkan oleh rentetan *Aggressive Buy*.

### B. TechnicalAnalyzer.kt (Analisis Teknikal)
* **Data Feed:** Terintegrasi dengan Yahoo Finance API (`YahooFinanceRepository`) untuk menarik data *intraday candles*. Menggunakan sistem *caching* (hanya untuk *candles*) untuk efisiensi jaringan.
* **Indikator:** Menghitung VWAP, MFI, EMA (9 & 21), Bollinger Bands (Multipliers), MACD (Histogram), dan Supertrend.
* **Fibonacci & S/R:** Menghitung level Fibonacci *Retracement* secara dinamis berdasarkan 50 *candle* terakhir.
* **Tick Rounding (Fraksi Harga):** Mengimplementasikan pembulatan matematis (`PriceFraction.kt`) agar nilai *Support* dan *Resistance* selalu valid dengan fraksi harga Bursa Efek Indonesia (misal: kelipatan Rp 25 untuk saham > 5000).

### C. ScoringEngine.kt (Sistem Penilaian & Sinyal)
* **Scoring System (0-100):** Menggabungkan skor OrderFlow, Teknikal, dan Kondisi Sesi Pasar.
* **Rekomendasi (Hysteresis):** Memberikan sinyal STRONG BUY, BUY, WATCH, dan AVOID. Dilengkapi dengan logika *Anti-Flicker* (Hysteresis) agar sinyal tidak berkedip-kedip saat skor berada di ambang batas.
* **Trading Plan:** 
  * **Entry Price:** Selalu mengikuti harga *real-time*.
  * **Target Price:** Menggunakan tembok *offer* terdekat atau resisten teknikal terdekat.
  * **Stop Loss (SL):** Berlindung 1 *tick* di bawah tembok *bid* raksasa (> Rp 500 Juta) atau di titik *support* teknikal.
  * **Gaya Trading Dinamis:** Secara otomatis melabeli strategi *Buy on Breakout*, *Buy on Weakness*, *Buy on Support*, atau *Momentum*.
* **Panic Bailout:** Fitur darurat yang otomatis menurunkan sinyal jika mendeteksi guyuran transaksi jual > Rp 500 Juta setelah sinyal Buy diberikan.

## 3. User Interface (UI)
* **Dual Layout:** Memisahkan *orderbook injector* di latar belakang dan memunculkan *Dashboard* interaktif.
* **3-Tab Navigation:**
  * **Manual:** Input *ticker* secara manual.
  * **Movers:** Daftar saham teraktif (*Top Gainers/Losers*).
  * **Top Picks:** Sinyal *AI Recommended* saham-saham pilihan yang layak di-*scalping*.
* **Detail Bottom Sheet:** Panel *pop-up* yang menampilkan skor, *Trading Plan* (Entry, Target, SL), tingkat *Risk/Reward*, Alasan AI, Peringatan Risiko, dan level S/R.

## 4. Daftar Bug Kritis yang Diselesaikan (Bug Fixes)
1. **Movers Panel Blank:** Diperbaiki dengan memaksa `webViewMovers` menggunakan *Desktop User-Agent* karena Stockbit memblokir sidebar pada versi *mobile*.
2. **Signal Flickering:** Diperbaiki dengan *Hysteresis cascade logic*. Sinyal *Strong Buy* kini akan turun ke *Buy* atau *Watch* secara bertahap saat skor turun, alih-alih langsung anjlok ke *Avoid*.
3. **Stale Technical Cache (Bug Support/Resisten Ngawur):** Pada awalnya, mesin menyimpan *TechnicalResult* secara permanen sehingga S/R yang dihitung dari data harga pertama kali terhubung ikut terkunci (menyebabkan S1/R1 anomali seperti Rp 126 di saham 900-an). Ini diperbaiki dengan menghapus *cache technical*, dan memaksa perhitungan ulang secara dinamis menggunakan harga terbaru (*lastPrice*).
4. **Hardcoded "Buy on Breakout":** Tulisan *Gaya Trading* sebelumnya terkunci permanen di tampilan XML. Telah diperbaiki dengan menghubungkan logika *OrderFlow* dan *Technical* sehingga gaya *entry* (*Breakout/Weakness/Support*) tampil sesuai fakta yang terjadi di orderbook.
5. **Fractional Tick Error:** Angka resisten dan support Fibonacci sering menampilkan desimal aneh (misal: 20.262). Telah diperbaiki dengan fungsi pembulatan (*Tick Rounding*) agar angkanya mematuhi aturan fraksi kelipatan Bursa Efek Indonesia.

## 5. Rencana & Pengujian Selanjutnya (Next Steps)
* Pengujian *Paper Trading* (simulasi tanpa uang asli) selama 2-3 hari di jam kerja bursa untuk mengukur *win rate* dan akurasi AI.
* Pemantauan ketahanan mesin saat menghadapi saham super volatil (misalnya, saham IPO atau saham gorengan).
* Optimalisasi beban *WebView* jika aplikasi dirasa berat pada *smartphone* berspesifikasi menengah ke bawah.

---
*Dokumen ini dibuat secara otomatis pada akhir sesi pengembangan tahap pertama.*
