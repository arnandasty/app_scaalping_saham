# CHANGELOG - Scalping Assistant Android App

Semua perubahan signifikan dicatat di sini secara kronologis.

---

## [v2.0] - 2026-09-06 (Terkini)

### Local WebSocket Intercept, Live Bandar Detector, & Anti-Pucuk Rules
- **Local WebView Network Intercept:** Injeksi `stream_probe.js` untuk mencegat `window.WebSocket`, `EventSource`, `fetch`, dan `XMLHttpRequest` langsung di Android WebView tanpa server cloud luar (100% Free & Zero-Latency).
- **Integrasi Live Bandar Detector & Broker Flow:** Auto-fetch dan parsing endpoint resmi `exodus.stockbit.com/marketdetectors/{ticker}` untuk status akumulasi/distribusi (`Big Acc`, `Acc`, `Neutral`, `Dist`, `Big Dist`), rata-rata harga modal bandar (*Average Price*), dan nilai transaksi Rupiah.
- **Strategi Scalping "Akan Naik" (Early Momentum):** Prioritas saham dengan kenaikan awal +0.5% s/d +5.0% yang didukung volume dan konfirmasi akumulasi bandar (bonus skor +8 & gaya `Early Momentum`).
- **Proteksi Anti-Pucuk (Anti-FOMO Hard Block):** Saham $\ge +7.0\%$ yang masih berada di pucuk harian (*High*) dilarang keras memunculkan rekomendasi `BUY/STRONG BUY` dan dikunci ke `WATCH` dengan peringatan *"Dilarang beli di pucuk — tunggu pullback ke support"*.
- **Pengecualian Super Momentum (Ride the Wave):** Membuka kunci rekomendasi `BUY/STRONG BUY` untuk saham laju $\ge +7.0\%$ yang terkonfirmasi didorong HAKA masif dan akumulasi bandar menuju ARA, lengkap dengan Stop Loss super ketat 1-2 tik (-1.2% s/d -1.5%).
- **Pembenahan Total Alasan Rekomendasi AI:** Mengeliminasi kontradiksi akumulasi vs distribusi, memprioritaskan alasan nomor 1 yang selaras dengan rekomendasi, menghapus alarm volume palsu di pagi hari, dan menghilangkan angka "Rp 0" pada Support/Resisten.
- **UI & Detail Bottom Sheet:** Lencana live bandar pada kartu daftar saham (`chipBandar`) dan panel institusional baru *Bandar Detector (Live Stream)* pada lembar detail emiten.
- **Panduan Pengujian:** Menambahkan dokumen resmi `PANDUAN_PENGUJIAN_SENIN.md` untuk skenario uji live market pembukaan bursa.

---

## [v1.5] - 2026-09-04

### 5-Layer Signal Filter (Anti False Strong Buy)
Merespons false signal Strong Buy: CUAN beli 975 turun ke 930, COCO beli 134 turun ke 132.

**Filter 1 - Volume Ratio**
- Volume < 50% rata-rata 5 hari -> penalty -15 skor
- Volume < 80% -> penalty -10 skor
- Volume < 100% -> blokir Strong Buy

**Filter 2 - EMA 50 Tren Makro**
- Jika harga di bawah EMA 50 -> tren mayor bearish -> sinyal dikunci maks WATCH
- Tidak bisa tampil BUY/STRONG_BUY selama di bawah EMA 50

**Filter 3 - Cooldown Drop dari High Hari Ini**
- Harga drop 2%+ dari high -> penalty -8 skor + warning
- Harga drop 3%+ dari high -> penalty -15 skor + blokir Strong Buy

**Filter 4 - Double Confirm (2 Snapshot)**
- Strong Buy hanya valid jika snapshot sebelumnya juga BUY/STRONG_BUY
- Mencegah lonjakan sinyal sesaat yang tidak berkelanjutan

**Filter 5 - Threshold Skor Lebih Ketat**
- STRONG_BUY: >= 82 -> >= 88
- BUY: >= 68 -> >= 75
- Hysteresis STRONG_BUY: >= 65 -> >= 72
- Hysteresis BUY: >= 45 -> >= 52

**Warning Otomatis di UI:**
- Harga di bawah EMA 50 - tren mayor BEARISH. Sinyal BUY dikunci, max WATCH.
- Volume sangat sepi dari rata-rata 5 hari. Sinyal kurang valid!
- Harga sudah drop X.X% dari high hari ini. Strong Buy dikunci!
- Volume tinggi dari rata-rata - konfirmasi kuat!

File diubah: TechnicalData.kt, TechnicalAnalyzer.kt, ScoringEngine.kt

---

## [v1.4] - 2026-09-04

### Support 2 & Resistance 2 (Indikator Dinamis)
- S1 & R1: Tetap dari kalkulasi Fibonacci (stabil)
- S2 & R2: Dari kombinasi VWAP, EMA 9, EMA 21, Bollinger Band Lower/Upper
- Ditampilkan di kartu Detail setiap saham

### Sub-Tab Track Record di Porto
- 2 sub-tab: Posisi Aktif dan Track Record
- Saat TP/CL: posisi berpindah ke Track Record dengan P&L final terkunci
- Di Track Record: tampilkan histori riwayat harga keluar & status

### Jeda Notifikasi Cut Loss (Anti Spam)
- Notifikasi cut loss/bailout minimal jeda 2 menit per emiten

File diubah: OrderBookRepository.kt, PortfolioFragment.kt, PortfolioAdapter.kt,
             item_portfolio_trade.xml, fragment_portfolio.xml, DetailBottomSheet.kt,
             TechnicalAnalyzer.kt, TechnicalData.kt

---

## [v1.3] - 2026-09-04

### Harga Realtime di Tab Movers
- Tab Movers kini menampilkan harga saham terkini yang akurat dari berbagai emiten

### Portfolio Persistence (SharedPreferences)
- Data portfolio tersimpan meski aplikasi ditutup/restart

### Tape Reading (HAKA/HAKI)
- Integrasi data running trade (transaksi berjalan) dari WebView stream
- Deteksi HAKA (beli masif) dan HAKI (jual masif)
- Skor Tape Reading +15 jika HAKA dominan, -20 jika HAKI dominan

File diubah: OrderBookRepository.kt, MainActivity.kt, TapeReadingStat.kt

---

## [v1.2] - 2026-09-03

### Dynamic Entry & Target Price Strategy
- Entry Price: prioritas tembok bid tebal -> breakout -> pullback teknikal
- Target Price: minimum 2.0%, normal 2.8%, diperhitungkan tembok offer
- Stop Loss: di bawah tembok bid 1 tick, maks 1.5% dari entry

### Anti-Flicker Hysteresis
- Rekomendasi tidak mudah flicker tiap detik

### Perbaikan Fibonacci Support/Resistance
- Dibulatkan ke tick size valid sesuai fraksi harga IDX
- Perbaikan bug resistance yang berubah-ubah terus

File diubah: ScoringEngine.kt, PriceFraction.kt, TechnicalAnalyzer.kt

---

## [v1.1] - 2026-09-02

### Tab Movers (Saham Paling Bergerak)
- WebView terpisah untuk scraping top movers dari Stockbit
- Pipeline 2 fase: ambil ticker dari sidebar -> scrape orderbook lengkap
- Fallback ke analisis teknikal saja jika market belum buka

### Tab Top Picks
- Aggregasi dari Manual + Movers, tampilkan 5 saham terbaik

### Portfolio Tracker
- Input lot dan harga entry manual
- Monitor P&L realtime (Rupiah & Persen)
- AI Action otomatis: TAHAN / TAKE PROFIT / CUT LOSS
- Tombol aksi cepat di setiap kartu portfolio

### Bailout Alert (Deteksi Guyuran)
- Deteksi otomatis guyuran besar dari bandar
- Konfirmasi 3 snapshot berturut-turut (anti false alarm)
- Cooldown 2 menit setelah beli sebelum alert aktif

---

## [v1.0] - 2026-09-01

### Initial Release - Scalping Assistant Android

Arsitektur Dasar:
- MainActivity: Host multi-tab (Manual, Movers, Top Picks, Portfolio)
- WebView: Inject JavaScript ke Stockbit untuk scrape orderbook realtime
- OrderFlowAnalyzer: Analisis aliran order (delta, fake wall, akumulasi)
- TechnicalAnalyzer: EMA 9/21, Bollinger Band, MACD, Supertrend, Fibonacci
- ScoringEngine: Gabungkan semua skor jadi rekomendasi (0-100)
- OrderBookRepository: Repository utama, state flows untuk UI

Indikator Teknikal:
- EMA 9 & EMA 21 (Golden/Death Cross)
- Bollinger Bands (20, 2.0)
- MACD (12, 26, 9)
- Supertrend (ATR 10, Multiplier 3)
- Fibonacci Retracement (50 candle terakhir)
- VWAP & MFI (Money Flow Index)

Order Flow Analysis:
- Delta Volume Score (bid vs offer pressure)
- Fake Wall Detection
- Absorption & Akumulasi Detection
- Breakout Signal Detection

Rekomendasi System:
- STRONG_BUY: Sinyal kuat, entry langsung
- BUY: Sinyal baik, antre entry
- WATCH: Pantau, belum cukup sinyal
- AVOID: Hindari, kondisi bearish/berbahaya
