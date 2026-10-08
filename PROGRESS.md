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
| `TradingStyle.kt` | **Gaya trading** (Daytrade / Swing 3-10 hari) beserta bobot tiap komponen |
| `TradingViewScreenerRepository.kt` | Akses scanner IDX, 8 preset (4 per gaya), skoring hybrid dua tahap, cache 5 mnt, cache universe untuk pencarian |
| `ScreenerScoringEngine.kt` | Skor 0-100 dari 8 komponen indikator & struktur (bukan orderbook); bobot mengikuti gaya trading |
| `SmartMoneyAnalyzer.kt` | Deteksi swing, struktur HH/HL/LH/LL, BOS, CHoCH, Order Block, Fibonacci dua tarikan |
| `ScreenerFragment.kt` | Tab Screener: chip **Gaya** + chip preset + chip TF, kolom **pencarian saham**, dialog rincian skor |
| `ScreenerAdapter.kt` | Kartu hasil: badge skor, nama perusahaan, chip tren/OB, zona beli/jual Fibonacci, label gaya |
| `fragment_screener.xml` | Layout tab + peringatan delay yang selalu terlihat + kolom pencarian |
| `item_screener_stock.xml` | Layout kartu kandidat |
| `bg_score_green/yellow/red.xml` | Warna badge skor |
| `bg_dialog_sheet.xml` | Latar dialog rincian skor |

### D. Skoring (0-100) — BEDA dari `ScoringEngine.kt`

Skor di tab Screener **sengaja berbeda** dari `ScoringEngine.kt` milik aplikasi:

* `ScoringEngine.kt` → berbasis **orderbook** (bid/offer, delta volume, tembok) + sesi bursa + bandar detector.
* `ScreenerScoringEngine.kt` → murni berbasis **indikator & struktur pasar**.

#### D.1 Dua Gaya Trading — Bobot Berbeda

Tab Screener punya baris chip **Gaya** di paling atas. Yang berubah hanya **bobot**-nya; cara menilai tiap komponen (rasio 0..1 lalu dikali bobot) sama, sehingga angka 0-100 tetap bisa dibandingkan antar emiten.

| # | Komponen | Daytrade | Swing 3-10 hari | Dasar Penilaian |
| :-: | :--- | :-: | :-: | :--- |
| 1 | Volume / RVOL | **20** | 15 | RVOL ≥5× (penuh) s.d. <1× (nol) |
| 2 | Bandarmology | **18** | 15 | `MoneyFlow` 0-100 + rating teknikal sebagai konfirmasi |
| 3 | MACD | 12 | 10 | Histogram (`MACD.macd` - `MACD.signal`) di atas nol & di atas signal |
| 4 | RSI 14 | 10 | 10 | **Cara menilai berbeda antar gaya** — lihat di bawah |
| 5 | Moving Average | 8 | **18** | Susunan EMA9>EMA21 + harga di atas EMA20/50/200 |
| 6 | Fibonacci | 12 | **15** | Konfirmasi beli 0,5-0,618 pada tarikan premier |
| 7 | Smart Money (OB/BOS/CHoCH) | **15** | 12 | BOS bullish, CHoCH bullish, harga di dalam Order Block |
| 8 | Struktur Pasar | 5 | 5 | Bullish HH/HL (penuh), Ranging (40%), Bearish LH/LL (nol) |
| | **Total** | **100** | **100** | |

**Alasan perbedaan bobot:**
* **Daytrade** menekankan **Volume & Bandarmology** (likuiditas menentukan bisa/tidaknya keluar posisi) dan mengecilkan **Moving Average** (tren jangka panjang hampir tidak relevan untuk posisi singkat).
* **Swing 3-10 hari** menekankan **Moving Average & Fibonacci** (yang menopang harga selama beberapa hari) dan mengecilkan Volume (lonjakan sehari tidak terlalu berarti).

**Timeframe:** kedua gaya memakai **candle harian (1D)** sebagai default, tetapi chip TF kini juga menyediakan **15M** — lihat bagian E2.

**RSI dinilai berbeda (bukan hanya bobotnya):**
* **Daytrade** — RSI sangat rendah (oversold) dianggap **peluang pantulan cepat** (rasio 0,8), karena posisi hanya sebentar.
* **Swing** — RSI sangat rendah berarti **tren turun masih berlangsung** (rasio 0,2), karena harga masih perlu naik selama 3-10 hari.

#### D.2 Alur Hybrid Dua Tahap

1. **Tahap 1 (cepat)** — 1 request snapshot untuk seluruh universe → semua kandidat diskor dari indikator.
2. **Tahap 2 (akurat)** — 12 kandidat teratas diambil candle nyata sesuai TF terpilih, lalu komponen SMC/Order Block/Fibonacci dihitung ulang secara paralel.
3. Hasil akhir **selalu diurutkan dari skor tertinggi**.
4. Kartu tanpa analisis candle diberi tanda `snapshot` (komponen candle bernilai 0) vs `🧠 SMC <TF>`, agar perbandingan tetap jujur.
5. Cache dipisah per **preset + timeframe + gaya**, jadi hasil daytrade tidak pernah menimpa hasil swing.

**Batasan request (penting untuk kecepatan):**
* Tahap 2 hanya menganalisis **12 kandidat teratas** (`TIME_SAFE_DEEP_COUNT`), karena 1 emiten = 1 request candle ke Yahoo Finance dan Yahoo TIDAK bisa menggabungkan banyak emiten dalam satu request.
* Fitur pencarian memakai `deepLimit = 8` dengan alasan yang sama.

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

### E2. Timeframe: 15M atau 1D, dengan Jendela 100 Candle

Tab Screener bisa dibaca dari **dua kerangka**: intraday **15M** atau harian **1D**. Pilihan 15M ditambahkan karena pengujian menunjukkan sebagian emiten lebih akurat dinilai dari gerak 15 menit (mis. harga berbalik arah dalam beberapa jam sehingga tarikan Fibonacci harian sudah kedaluwarsa).

| Chip | Candle | Range | Candle terukur | Horizon |
| :--- | :--- | :--- | :-: | :--- |
| **1D** (default) | 1 hari | 3mo | ~67 | ~3 bulan |
| **1D** | 1 hari | 6mo | ~132 | ~6 bulan |
| **15M** | 15 menit | 1mo | ~640 | ~1 bulan |

**Jendela analisis diseragamkan 100 candle** untuk SEMUA pilihan (`CandleTimeframe.analysisWindow = 100`). Jadi yang berbeda antar chip hanyalah **satuan waktu & panjang riwayat yang diunduh** — bukan kedalaman analisisnya. Karena itu level Fibonacci 15M dan 1D bisa dibandingkan langsung.

> Catatan teknis: 640 candle dari 15M/1mo dipangkas otomatis oleh jendela 100 candle, sehingga perhitungan tetap ringan meski unduhannya lebih besar.

Kedua gaya trading (Daytrade & Swing) memakai default yang sama, yaitu **1D / 3 bulan**. Pembeda antar gaya adalah **bobot skor** dan **preset penyaring**, bukan timeframe.

> Pipeline Scalping di tab Manual/Movers **tetap memakai candle 15 menit (15m/5d, `INTRADAY`)** karena butuh data per menit untuk membaca orderbook. Tab Screener memakai entri terpisah (`INTRADAY_BULAN1` = 15m/1mo) supaya riwayat intraday-nya cukup panjang untuk jendela 100 candle, dan perubahan di tab Screener tidak menambah beban pipeline Scalping.

**⚠️ Temuan terukur — kenapa minimal 3 bulan:**

IDX hanya buka ~20-21 hari/bulan, jadi 1 bulan ≈ 21 candle harian. Deteksi swing butuh 3 candle kiri + 3 kanan, sehingga 6 candle awal & akhir tidak bisa jadi titik swing.

| Jangka | Candle D1 | Hasil uji (ANTM / BBRI / TLKM) |
| :--- | :-: | :--- |
| 1 bulan | 23 | OVERSHOOT ❌ / OVERSHOOT ❌ / WAITING 🟡 |
| 2 bulan | 43 | OVERSHOOT ❌ / OVERSHOOT ❌ / OVERSHOOT ❌ |
| **3 bulan** | **66** | **VALID ✅ / VALID ✅** / OVERSHOOT |
| 6 bulan | 128 | WAITING / WAITING / OVERSHOOT |

**Kesimpulan:** minimal **~60 candle (≈3 bulan)** agar pola premier + tarikan sekunder bisa diandalkan. Karena itu **1D / 3 bulan** tetap dijadikan **default**, sementara **15M / 1 bulan** disediakan sebagai alternatif yang bisa dipilih pengguna.

### E3. Catatan Kecepatan (terukur, 7 Okt 2026)

Sempat dilaporkan tab Screener terasa lama memuat. Setelah diukur langsung ke TradingView & Yahoo Finance:

| Yang diukur | Waktu |
| :--- | :-: |
| Scanner TradingView, 1 request (60 baris) | 396 ms |
| Scanner TradingView, universe pencarian (844 baris, 293 KB) | 1.287 ms |
| 12 emiten candle harian paralel | 197 ms |
| 5x berturut-turut (simulasi ganti preset) | stabil ~100 ms, semua HTTP 200 |
| 30 emiten candle sekaligus | 156 ms |

**Temuan:** jaringan cepat dan Yahoo **tidak** membatasi permintaan berulang (tidak ada HTTP 429/999). Jadi lambatnya berasal dari kondisi jaringan pengguna, **bukan** dari jumlah candle.

**Perbaikan yang tetap dipasang** (mengurangi jumlah request, bukan mengubah data):
1. **Deduplikasi request (single-flight)** — request candle yang identik dan sedang berjalan dipakai bersama lewat `ConcurrentHashMap<String, CompletableDeferred>` di `YahooFinanceRepository`. Sebelumnya satu emiten bisa diminta 2x saat tab Screener baru dibuka (pipeline Scalping minta 15m, Screener minta 1D).
2. **Batas analisis candle hasil pencarian** — `scoreStocks(deepLimit = 8)`: hanya 8 kandidat teratas yang dianalisis dengan candle nyata; sisanya tetap tampil bertanda `snapshot`. Alasan: 1 emiten = 1 request, jadi 30 hasil sekaligus = 30 request.

> Catatan: memotong jumlah candle **tidak** dipakai sebagai solusi, karena candle harian 3 bulan hanya ~4,5 KB / 66 candle — sangat ringan. Yang berpengaruh adalah jumlah *request*, bukan besar datanya.

### E4. Perbaikan Anchor Fibonacci: Pivot ZigZag + Dua Titik Terakhir

**Masalah yang dilaporkan:** kedua tarikan masih mengambil acuan high lama. Contoh: harga hari ini naik ke **150**, tetapi tarikan tetap ditarik ke high sebelumnya di **136** — sehingga zona beli (tarikan 1) dan target jual (tarikan 2) sama-sama tertinggal. Titik *low* sudah benar; hanya titik *high* yang salah baca.

**Akar masalah:** `findSwings` mensyaratkan sebuah candle puncak **lebih tinggi dari candle di kanannya**. Akibatnya puncak **terbaru** (high hari ini) belum pernah dianggap swing selama harga belum turun. Fibonacci pun mengunci high lama yang sudah "terkonfirmasi".

**Perbaikan (2 tahap):**

1. **Anchor high** diambil dari **pivot ZigZag** (`findZigZagPivots`) — titik balik terkonfirmasi berbalik ≥ ambang, **ditambah** ekstrem yang masih berjalan di ujung kanan (mis. high hari ini), sehingga high terbaru langsung terbaca.
2. **Anchor low (zona beli) memakai DUA TITIK TERAKHIR:** pivot low/higher low **terdekat sebelum high terbaru** (`lowestPriorLow`), bukan titik low terendah sepanjang tren. Jadi saat harga mencetak higher high baru dan koreksi belum terjadi, Fibonacci ditarik dari higher low terdekat ke high terbaru — zona beli menempel di harga terkini. Bila leg ini kurang signifikan, barulah ditelusuri high yang lebih tua sebagai cadangan.

| Aspek | Sebelum | Sesudah |
| :--- | :--- | :--- |
| Sumber high premier | Swing high (butuh candle kanan lebih rendah) | Pivot ZigZag + ekstrem berjalan |
| High hari ini (150) | Tidak terbaca, tertinggal di 136 | Langsung terbaca |
| Anchor low (zona beli) | Titik low **terendah** sepanjang tren (mis. 99) | **Higher low terdekat** sebelum high (mis. 129) |
| Zona beli saat harga di high baru | Jauh di bawah harga (118–124) | **Menempel di harga (137–139,5)** |
| Zona jual saat high berjalan | Kosong (tidak ada koreksi sesudah high) | Diukur dari dasar leg berjalan |

**Ambang ZigZag** = `1,5 × rata-rata rentang candle (high−low)`, dibatasi **0,6%–3,0%**. Rentang candle dipakai (bukan gerak close-ke-close) supaya noise intrabar kecil tidak memecah satu ayunan menjadi banyak pivot palsu.

**Zona jual (tarikan 2) saat high masih berjalan:** bila koreksi setelah high **belum** terjadi, zona jual diukur dari **dasar leg berjalan** (pivot low terakhir sebelum high) — sehingga target profit ikut terangkat bersama high baru, bukan hilang/tertinggal. Zona ini **ditandai eksplisit** `secondaryLegProvisional`, jadi di kartu/dialog muncul sebagai **"proyeksi target"** dengan keterangan "pola sekunder belum terbentuk", bukan sebagai zona jual sekunder yang sah.

**Audit kelima syarat Fibonacci:**

| # | Syarat | Status |
| :-: | :--- | :--- |
| 1 | Pola premier (kenaikan signifikan setelah swing low) | ✅ Ditegakkan (`findPremierLeg`: dua titik terakhir + lonjakan ≥ ambang adaptif) |
| 2 | Pola sekunder (koreksi setelah pola premier) | ✅ Ditegakkan; bila belum terbentuk, zona jual diberi label **proyeksi** |
| 3 | Tarikan 1 = LOW PREMIER → HIGH PREMIER (zona beli) | ✅ |
| 4 | Tunggu harga tembus < 0,5 dan tidak lebih rendah dari 0,618 | ✅ (`dippedBelow05` & `heldAbove0618`) |
| 5 | Tarikan 2 = HIGH SEKUNDER → LOW SEKUNDER (zona jual) | ✅ |

**Uji data nyata (1D/3mo, jendela 100 candle) — zona beli kini jatuh di sekitar harga terkini:**

| Emiten | Close | Zona Beli SEBELUM | Zona Beli SESUDAH |
| :--- | :-: | :--- | :--- |
| TLKM | 2260 | 2609–2630 ❌ (di atas harga) | **2253–2270** ✅ |
| SMRA | 236 | 304–312 ❌ | **237–238** ✅ |
| BBRI | 3030 | 3036–3075 | **3013–3045** ✅ |
| GOTO | 30 | `NONE` ❌ | **28–29** ✅ |

**Uji skenario sintetis (high lama 136 → high hari ini 150):** tarikan 1 naik dari high 133 ke **150**, zona beli 112–116 → **118–125**, dan zona jual tetap ada (140–142) ✅.

### F. Preset Penyaringan (Berbeda per Gaya)

Preset dibangun ulang saat chip **Gaya** diganti, jadi kandidat yang disaring sejak awal memang cocok dengan gaya yang dipilih.

**Gaya Daytrade:**

| Preset | Filter |
| :--- | :--- |
| 💧 Likuid | vol >5 Jt, perubahan -3% s.d. +6% |
| 🚀 Momentum | naik +1% s.d. +7%, RVOL 2-50×, vol >3 Jt |
| 🔄 Reversal | turun <-1%, RVOL >1,5×, vol >2 Jt |
| Semua Aktif | vol >1 Jt |

**Gaya Swing 3-10 hari:**

| Preset | Filter |
| :--- | :--- |
| ✅ Tren Naik | `Recommend.All` >0,2, RSI 45-70, vol >500 Rb |
| 🎯 Pullback | RSI 35-50, `Recommend.All` >0, vol >500 Rb |
| 🧠 Akumulasi | `MoneyFlow` >60, RSI 40-65, vol >500 Rb |
| Semua Aktif | vol >500 Rb |

* **Batas atas RVOL 50×** dipasang untuk membuang saham baru listing — verifikasi menemukan `IDX:ENAK` bernilai 122× (tidak wajar).
* Setiap filter di atas **terverifikasi HTTP 200** pada scanner IDX (jumlah hasil: Likuid 100, Momentum 20, Reversal 13, Tren 100, Pullback 17, Akumulasi 100).

### F2. Pencarian Kode / Nama Saham

Kolom **🔍 Cari kode / nama saham** di bawah baris peringatan delay.

* **Cara kerja:** hasil pencarian bekerja **di dalam HP**, bukan dengan request filter ke server. Seluruh universe IDX (844 emiten, 22 kolom) diunduh **sekali** lalu disimpan di cache memori 10 menit.
* **Terukur:** 1 request `range [0,900]` = **~268 KB, ~763 ms** (HTTP 200). Karena itu mengetik terasa instan.
* **Cocok untuk:** kode (`BBCA`), potongan kode (`BCA`), dan **nama perusahaan** (`bank central`, `telkom`) — kolom `description` berisi nama lengkap, mis. `PT GoTo Gojek Tokopedia Tbk`.
* **Kenapa bukan filter server:** filter `left="name"` bersifat **case-sensitive** dan hanya menerima kode persis — `"BBCA"` → 1 baris, `"bbca"` → 0 baris, `"IDX:BBCA"` → 0, `"BBCA.JK"` → 0. Filter itu juga tidak bisa mencocokkan nama perusahaan.
* **Debounce 300 ms** agar tidak dicari pada tiap huruf saat mengetik cepat; tombol **✕** mengosongkan kolom.
* Hasil pencarian ditampilkan memakai **format kartu yang sama** seperti hasil screening (dan langsung dinilai dengan gaya + TF harian yang aktif), karena `scoreStocks()` melewati filter preset — tujuannya menganalisis emiten pilihan pengguna, bukan menyaring.
* **Dibatasi `deepLimit = 8`**: hanya 8 hasil teratas yang dianalisis dengan candle nyata, karena 1 emiten = 1 request jaringan. Sisanya tetap tampil bertanda `snapshot`.
* Baris keterangan di atas daftar selalu memberi tahu mode yang aktif: "Hasil pencarian …" atau "Gaya … · bobot Volume …".

### G. Alur Integrasi
1. Tab **Screener** berada di indeks 3 (setelah Top Picks) → **Portfolio bergeser ke indeks 4**.
2. Saat tab dibuka: gaya **Swing 3-10 hari** aktif, preset pertama (✅ Tren Naik) dimuat otomatis dengan candle harian **1D / ~3 bulan**.
3. Mengganti chip **Gaya** → preset ikut berganti, daftar dimuat ulang. Chip **TF** berisi **15M (~1 bulan)**, **1D (~3 bulan, default)**, dan **1D (~6 bulan)** — semuanya dianalisis dengan jendela 100 candle terakhir.
4. Menekan kartu → muncul **dialog rincian skor** (poin & alasan tiap komponen + level Fibonacci dua tarikan + gaya & TF yang dipakai). Tombol "Buka di Stockbit" memindahkan ke tab Manual + membuka emiten di orderbook + meminta data Bandar Detector.
5. Setelah dibuka, emiten melewati pipeline lama (OrderFlow + Teknikal + ScoringEngine) sehingga sinyal entry tetap berbasis data real-time.

### H. Status
* ✅ APK debug berhasil di-build (`app-debug.apk`) — kompilasi Kotlin & resource tervalidasi.
* ✅ Logika SMC & Fibonacci dua tarikan divalidasi dengan candle nyata (skrip port Node di folder scratch artifact) pada 6 emiten: BBCA, BBRI, ANTM, TLKM, GOTO, SMRA.
* ✅ Pemilih timeframe divalidasi lintas timeframe pada ANTM, BBRI, TLKM → jadi dasar keputusan memakai **candle harian 1D minimal 3 bulan** sebagai default, dengan **15M** tersedia sebagai alternatif.
* ✅ **Dua gaya trading** (Daytrade & Swing 3-10 hari) dengan bobot berbeda + 8 preset per gaya — seluruh filter terverifikasi HTTP 200 pada scanner IDX.
* ✅ **Pencarian kode/nama saham** berbasis cache universe (844 emiten, ~293 KB, ~1,3 dtk) — terverifikasi `name` bersifat case-sensitive sehingga pencarian sengaja dilakukan di sisi HP.
* ✅ **Kecepatan diukur** ke TradingView & Yahoo: 12 candle paralel ~197 ms, 30 candle ~156 ms, 5x berturut-turut stabil ~100 ms. Yahoo **tidak** membatasi permintaan (tidak ada HTTP 429/999), jadi keluhan lambat sebelumnya berasal dari jaringan pengguna.
* ✅ **Deduplikasi request candle** (single-flight) + batas analisis pencarian (8) mengurangi jumlah request, bukan mengubah data.
* ⏳ **Belum diuji di device/live market.** Perlu pengujian saat bursa buka.

---

## 8. Migrasi AI: "Groq AI" → "Advisor AI" (9Router / Endpoint OpenAI-compatible)

**Latar belakang:** fitur opini AI (Opini Scalper, Analisis Swing EOD, dan Dokter Portfolio Rescue) semula dipaku ke Groq Cloud (model & endpoint hardcoded). Migrasi ini menjadikannya **generik OpenAI-compatible** supaya bisa diarahkan ke **9Router** — proxy model yang berjalan di PC/laptop (`npm install -g 9router`, lalu `9router`, dashboard di `http://localhost:20128`) — yang menyediakan akses model Claude 4.5, GLM, GPT, dan MiniMax.

### A. Komponen
| Berkas | Perubahan |
| :--- | :--- |
| `AiConfig.kt` (baru) | Sumber tunggal Base URL + API Key + Model. Menormalkan alamat (`ip:20128`, `.../v1`, atau URL penuh `chat/completions`). Menyimpan setelan di `SharedPreferences` (`ai_base_url`, `ai_api_key`, `ai_model`). |
| `AiRepository.kt` | Hasil rename dari `GroqAiRepository.kt`. Enum `GroqAnalysisMode` → `AiAnalysisMode`. Semua panggilan chat memakai endpoint & model dari `AiConfig`. |
| `DetailBottomSheet.kt` | Dialog ⚙️ kini menyunting **Base URL + Model + API Key** (sebelumnya hanya kunci Groq). |
| `PortfolioRescueBottomSheet.kt` | Ikut memakai `AiConfig` + `AiRepository` (sebelumnya membaca `groq_api_key` langsung). |

### B. Perilaku Penting
1. **Rantai model (auto-fallback):** model aktif dicoba lebih dulu, lalu daftar model cadangan (`kr/claude-sonnet-4.5`, `kr/claude-haiku-4.5`, `oc/gpt-5`, `glm/glm-4.6`). Server yang menolak model (HTTP 404 atau pesan berisi "model") dilewati otomatis.
2. **API Key opsional untuk 9Router:** bila Base URL **bukan** Groq Cloud, analisis tetap boleh jalan tanpa API Key (header `Authorization` tidak dikirim). Penekanan "key wajib" hanya muncul bila Base URL mengarah ke `groq.com`.
3. **Alamat default = IP LAN PC** (`http://192.168.18.70:20128/v1`) karena `localhost` di HP menunjuk ke HP itu sendiri. Emulator Android memakai alias host `10.0.2.2`. Alamat bisa dikoreksi dari HP tanpa build ulang.
4. **Server 9Router harus dibuka ke LAN** bila ingin diakses dari HP: jalankan dengan `HOSTNAME=0.0.0.0`; kalau tidak, hanya bisa diakses dari PC itu sendiri.
5. **Kompatibilitas setelan lama:** API Key yang tersimpan pada kunci lama `groq_api_key` tetap dibaca sebagai nilai awal, jadi pengguna lama tidak perlu mengisi ulang.

### C. Status
* ✅ APK debug berhasil di-build setelah migrasi (kompilasi Kotlin & resource tervalidasi).
* ✅ Referensi lama (`GroqAiRepository`, `GroqAnalysisMode`) sudah bersih; ID XML lama (`tvGroqSummary`, `btnAskGroq`, dst.) sengaja dipertahankan agar layout tidak perlu diubah.
* ⏳ Belum diuji live terhadap 9Router yang benar-benar berjalan — perlu uji koneksi dari HP ke PC.

---

## 9. Fitur Login Kode Redeem (Gerbang Akses Pengguna)

**Latar belakang:** aplikasi perlu dibatasi hanya untuk pengguna yang berhak, dengan cara sesederhana mungkin bagi pembeli namun tetap terkontrol bagi developer. Solusinya: **login dengan kode redeem** yang diterbitkan developer, dengan **masa berlaku** yang bisa diatur dan **logout otomatis** saat kadaluarsa.

### A. Keputusan Desain
| Aspek | Keputusan |
| :--- | :--- |
| **Backend validasi** | Firebase **Cloud Firestore** (tidak bisa dimanipulasi dari HP). |
| **Pengelolaan kode** | **Firebase Console** saja — **tanpa panel admin kustom**. Console sudah setara "editor tabel" dan gratis. |
| **Terbit kode** | Alat CLI `tools/redeem/generate-code.js` (Node) → mencetak kode + JSON siap tempel. |
| **1 pengguna = 1 kode** | Diikat ke **1 perangkat** (device ID anonim, hash `ANDROID_ID + model + salt`). |
| **Anti manipulasi jam** | Patokan jam tertinggi disimpan; jam mundur > 2 menit → login ditolak. |
| **Tanpa `google-services.json`** | Firebase diinisialisasi **programatik** dari `LoginConfig`, agar konfigurasi terkumpul di satu berkas. |

### B. Komponen Baru
| Berkas | Peran |
| :--- | :--- |
| [`data/auth/LoginConfig.kt`](app/src/main/java/com/scalping/assistant/data/auth/LoginConfig.kt) | Sumber tunggal `PROJECT_ID`, `APPLICATION_ID`, `API_KEY`, nama koleksi, prefix kode, `isConfigured`. |
| [`data/auth/LoginModels.kt`](app/src/main/java/com/scalping/assistant/data/auth/LoginModels.kt) | `LoginFailReason`, `LoginResult`, `LoginSession`, dan util `LoginFormat` (normalisasi & format tanggal). |
| [`data/auth/LoginRepository.kt`](app/src/main/java/com/scalping/assistant/data/auth/LoginRepository.kt) | Verifikasi ke Firestore, sesi lokal, device binding, deteksi jam mundur, keputusan offline. |
| [`ui/LoginActivity.kt`](app/src/main/java/com/scalping/assistant/ui/LoginActivity.kt) | Layar login (kini **launcher**), animasi logo, auto-lanjut bila sesi masih sah. |
| [`res/layout/activity_login.xml`](app/src/main/res/layout/activity_login.xml) + 4 drawable | Tampilan login premium (gradasi, kartu kaca, animasi). |
| [`tools/redeem/`](tools/redeem/README.md) | Generator kode redeem + dokumentasi pemakaian. |
| [`SETUP_FIREBASE_LOGIN.md`](SETUP_FIREBASE_LOGIN.md) | Panduan aktivasi lengkap untuk developer. |

### C. Alur
1. **`LoginActivity`** menjadi **launcher** aplikasi. Bila ada sesi valid → langsung ke `MainActivity`; bila tidak → tampilkan form.
2. `LoginRepository.redeem()` membaca dokumen `redeem_codes/{KODE}`, memeriksa `active`, `expiresAt`, dan `deviceId`.
3. Aktivasi pertama mengikat kode ke perangkat & mencatat di koleksi `activations` (bila Rules mengizinkan).
4. Setiap `MainActivity.onCreate` memanggil `verifyStoredSession()`; kode kadaluarsa/dicabut → **otomatis logout** ke layar login.
5. **Keluar akun manual:** tekan lama lencana sesi di layar utama.

### D. Keamanan
- `API_KEY` Firestore pada Android **bukan rahasia** — perlindungan bertumpu pada **Security Rules** (contoh lengkap di `SETUP_FIREBASE_LOGIN.md`).
- `expiresAt` diterima sebagai **number** (epoch millis) maupun **timestamp** Firestore, agar tidak salah baca tipe field.
- Deteksi jam mundur mencegah pengguna "memperpanjang" masa berlaku dengan memundurkan jam HP.

### E. Status
- ✅ **APK debug berhasil di-build** setelah fitur login ditambahkan (kompilasi Kotlin & resource tervalidasi).
- ✅ Generator kode diuji: mencetak kode `SCLPZYB8X4DS` + JSON Firestore dengan benar.
- ✅ **Mode pengembangan:** selama `LoginConfig.PROJECT_ID` kosong, login **dilewati** — aktivasi fitur cukup mengisi 3 nilai konfigurasi.
- ⏳ Belum diuji dengan Firebase sungguhan (perlu project Firebase milik developer untuk uji end-to-end).

---

*Dokumen ini diperbarui secara berkala dan mencakup seluruh perkembangan arsitektur dan strategi scalping.*
