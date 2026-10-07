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

## 5. Upgrade V2: Local WebSocket Intercept, Bandar Detector, & Strategi Anti-Pucuk
1. **Local WebView Stream Intercept (Zero-Cost, Zero-Server):**
   - Menginjeksi `stream_probe.js` ke Chromium WebView untuk mencegat `window.WebSocket`, `window.EventSource`, `fetch`, dan `XMLHttpRequest`.
   - Mengeliminasi kebutuhan server cloud (AWS EC2/FastAPI) dan menghindari resiko pemblokiran IP oleh Cloudflare.
   - Mengalirkan stream data langsung ke Kotlin via `AndroidProbe`.
2. **Integrasi Live Bandar Detector & Broker Distribution:**
   - Menghubungkan endpoint resmi `exodus.stockbit.com/marketdetectors/{ticker}` secara lokal menggunakan sesi login aktif pengguna.
   - Menarik status akumulasi/distribusi (`Big Acc`, `Acc`, `Neutral`, `Dist`, `Big Dist`), rata-rata harga modal bandar (*Average Price*), dan nominal transaksi (Rupiah).
3. **Strategi Scalping "Akan Naik" (Early Momentum):**
   - Filter ketat saham kenaikan awal **+0.5% s/d +5.0%** dengan lonjakan volume dan konfirmasi akumulasi bandar.
   - Diberikan bonus skor (+8) dan gaya `🎯 Early Momentum (Akan Naik)`.
4. **Proteksi Anti-Pucuk (Anti-FOMO Hard Block):**
   - Saham $\ge +7.0\%$ yang masih berada di area *High* otomatis dikunci ke `WATCH`. Tidak ada rekomendasi `BUY` di pucuk.
5. **Analisis Pullback Sehat vs Guyuran:**
   - Koreksi $1.5\% - 5.5\%$ dianalisis: Jika bandar jualan masif $\rightarrow$ `AVOID` (Guyuran). Jika bandar bertahan di bantalan support $\rightarrow$ `BUY` (Buy on Pullback).

---

## 6. Rencana & Pengujian Live Market (Senin, 09:00 WIB)
* **Checklist Pengujian:** Lihat panduan lengkap di [`PANDUAN_PENGUJIAN_SENIN.md`](file:///d:/WEB%20SAYA/info_saham/app_scalping/PANDUAN_PENGUJIAN_SENIN.md).
* **Fokus Utama:** Menguji debit aliran *Running Trade tick-by-tick* saat bursa resmi dibuka pukul 09:00:00 WIB, memvalidasi akurasi status akumulasi bandar, serta menguji respon proteksi Anti-Pucuk pada saham-saham yang melonjak tinggi.

---

## 7. Integrasi TradingView Screener (Universe IHSG)

### A. Latar Belakang & Verifikasi
* **Tujuan:** Menyaring kandidat scalping dari **seluruh 844 emiten IDX**, bukan hanya saham teraktif dari Stockbit.
* **Verifikasi kolom:** Seluruh **66 kolom** yang diuji diterima server (0 ditolak), sehingga skema kolom TradingView stabil dan permisif.
* **⚠️ Temuan Kritis — Data Delay 10 Menit:**
  * Payload WS quote `IDX:BBCA` mengembalikan `update_mode: "delayed_streaming_600"` dan feed bernama `IDX_DLY:BBCA`.
  * **Konsekuensi:** Harga dari TradingView tertinggal 10 menit. Karena itu screening ini **hanya untuk menyaring kandidat**, bukan penentu harga entry. Harga terkini, orderbook, dan running trade tetap wajib dari Stockbit WebView.
  * Zona waktu TradingView adalah `Asia/Bangkok` (GMT+7) — identik dengan WIB, tidak perlu konversi.

### B. Operator Filter yang Terverifikasi
* **Diterima:** `equal`, `greater`, `less`, `in_range`
* **DITOLAK (HTTP 400):** `above`, `below`, `not_equal`, `match`, `crosses_up`, `crosses_down`

### C. Komponen Baru
| Berkas | Peran |
| :--- | :--- |
| `TradingViewScreenerRepository.kt` | Akses scanner IDX, 6 preset filter, skoring hybrid dua tahap, cache 5 menit |
| `ScreenerScoringEngine.kt` | Skor 0-100 dari 8 komponen indikator & struktur (bukan orderbook) |
| `SmartMoneyAnalyzer.kt` | Deteksi swing, struktur HH/HL/LH/LL, BOS, CHoCH, Order Block, Fibonacci dua tarikan |
| `ScreenerFragment.kt` | Tab Screener, chip preset, dialog rincian skor per komponen |
| `ScreenerAdapter.kt` | Kartu hasil: badge skor, chip tren/OB, zona beli/jual Fibonacci |
| `fragment_screener.xml` | Layout tab + peringatan delay yang selalu terlihat |
| `item_screener_stock.xml` | Layout kartu kandidat |
| `bg_score_green/yellow/red.xml` | Warna badge skor |
| `bg_dialog_sheet.xml` | Latar dialog rincian skor |

### D. Skoring (0-100) — BEDA dari `ScoringEngine.kt`

Skor di tab Screener **sengaja berbeda** dari `ScoringEngine.kt` milik aplikasi:

* `ScoringEngine.kt` → berbasis **orderbook** (bid/offer, delta volume, tembok) + sesi bursa + bandar detector.
* `ScreenerScoringEngine.kt` → murni berbasis **indikator & struktur pasar**.

| # | Komponen | Bobot | Dasar Penilaian |
| :-: | :--- | :-: | :--- |
| 1 | Volume / RVOL | 20 | RVOL ≥5× (20) s.d. <1× (0) |
| 2 | Bandarmology | 15 | `MoneyFlow` 0-100 + rating teknikal sebagai konfirmasi |
| 3 | MACD | 10 | Histogram (`MACD.macd` - `MACD.signal`) di atas nol & di atas signal |
| 4 | RSI 14 | 10 | 60-77 paling ideal; >78 dianggap terlalu panas |
| 5 | Moving Average | 15 | Susunan EMA9>EMA21 + harga di atas EMA20/50/200 |
| 6 | Fibonacci | 10 | Konfirmasi beli 0,5-0,618 pada tarikan premier |
| 7 | Smart Money (OB/BOS/CHoCH) | 15 | BOS bullish (7), CHoCH bullish (5), harga di Order Block (3) |
| 8 | Struktur Pasar | 5 | Bullish HH/HL (5), Ranging (2), Bearish LH/LL (0) |
| | **Total** | **100** | |

**Grade:** ≥80 SANGAT KUAT 🔥 · ≥65 KUAT ✅ · ≥50 SEDANG 🟡 · ≥35 LEMAH ⚠️ · <35 SANGAT LEMAH ❌

**Alur hybrid dua tahap:**
1. **Tahap 1 (cepat)** — 1 request snapshot untuk seluruh universe → semua kandidat diskor dari indikator.
2. **Tahap 2 (akurat)** — 20 kandidat teratas diambil candle 15m nyata-nya, lalu komponen SMC/Order Block/Fibonacci dihitung ulang secara paralel.
3. Hasil akhir **selalu diurutkan dari skor tertinggi**.
4. Kartu tanpa analisis candle diberi tanda `snapshot` (skor maksimal 70) vs `🧠 SMC` (maksimal 100), agar perbandingan tetap jujur.

### E. Fibonacci DUA TARIKAN (sesuai ketentuan pemakaian)

Fibonacci **tidak** ditarik sekali dari rentang ekstrem, melainkan dua kali:

| Tarikan | Leg | Fungsi |
| :--- | :--- | :--- |
| **1 (premier)** | LOW PREMIER → HIGH PREMIER | **Zona beli** 0,5-0,618 |
| **2 (sekunder)** | HIGH SEKUNDER → LOW SEKUNDER | **Zona jual** 0,5-0,618 |

**Status setup yang dinilai:**

| Status | Arti | Poin |
| :--- | :--- | :-: |
| `VALID` | Sudah tembus ke bawah 0,5 **dan** tidak lebih rendah dari 0,618 | 10 |
| `WAITING` | Pola premier ada, belum koreksi ke 0,5 | 5 |
| `IN_SELL` | Harga sudah di zona jual tarikan sekunder (terlambat entry) | 2 |
| `OVERSHOOT` | Koreksi menembus 0,618 → setup beli gugur | 1 |
| `NONE` | Pola premier belum terbentuk | 0 |

Titik masuk (entry) dan titik jual (exit) ditampilkan langsung di kartu & dialog rincian skor.

**Ambang adaptif:** Definisi "kenaikan signifikan" dan "order block" memakai ambang adaptif = `0,8 × rata-rata gerak lookahead candle`, dibatasi 0,5%-3,0%. Kalibrasi pada 9 emiten IDX (BBCA ~0,8% s.d. GOTO ~3,7% per 5 candle) — ambang tetap membuat ANTM tidak pernah terdeteksi Order Block.

**Lookback swing adaptif:** Diturunkan otomatis bila swing terlalu sedikit, karena saham bertick kasar (harga puluhan rupiah, satuan 1 rupiah) punya banyak high/low kembar. Bila tidak ada swing low sebelum swing high, titik low premier memakai low candle terendah pada rentang tersebut — sehingga saham seperti GOTO (~Rp 30) tetap bisa dianalisis. Status tetap dilaporkan jujur `NONE` hanya bila kenaikannya memang tidak signifikan.

### F. Preset Penyaringan
| Preset | Filter |
| :--- | :--- |
| 🚀 Early Momentum | naik +0.5%–+5%, RVOL 2–50×, vol >1 Jt (selaras strategi "Akan Naik") |
| 🔥 Volume Spike | RVOL 3–50×, vol >1 Jt |
| 📈 Top Gainers | naik >3%, vol >500 Rb |
| ✅ Tren Naik | Recommend ≥0.3, RSI 45-75, vol >500 Rb |
| 💎 Oversold | RSI <35, vol >500 Rb |
| Semua Aktif | vol >1 Jt |

* **Batas atas RVOL 50×** dipasang untuk membuang saham baru listing — verifikasi menemukan `IDX:ENAK` bernilai 122× (tidak wajar).

### G. Alur Integrasi
1. Tab **Screener** berada di indeks 3 (setelah Top Picks) → **Portfolio bergeser ke indeks 4**.
2. Preset **Early Momentum** dimuat otomatis saat tab dibuka.
3. Menekan kartu → muncul **dialog rincian skor** (poin & alasan tiap komponen + level Fibonacci dua tarikan). Tombol "Buka di Stockbit" memindahkan ke tab Manual + membuka emiten di orderbook + meminta data Bandar Detector.
4. Setelah dibuka, emiten melewati pipeline lama (OrderFlow + Teknikal + ScoringEngine) sehingga sinyal entry tetap berbasis data real-time.

### H. Status
* ✅ APK debug berhasil di-build (`app-debug.apk`) — kompilasi Kotlin & resource tervalidasi.
* ✅ Logika SMC & Fibonacci dua tarikan divalidasi dengan candle 15m nyata (skrip port Node di folder scratch artifact) pada 6 emiten: BBCA, BBRI, ANTM, TLKM, GOTO, SMRA.
* ⏳ **Belum diuji di device/live market.** Perlu pengujian saat bursa buka.

---
*Dokumen ini diperbarui secara berkala dan mencakup seluruh perkembangan arsitektur dan strategi scalping.*
