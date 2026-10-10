# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Scalping Assistant AI** — native Android app (Kotlin, single `app` module, ViewBinding, minSdk 24 / compileSdk 36, Java 17) for scalping/swing analysis of Indonesian (IDX) stocks. Documentation, UI strings and comments are in **Bahasa Indonesia**; keep new user-facing text and docs in the same language.

## Commands

Run from the repo root (Windows: `.\gradlew`, bash: `./gradlew`):

```
.\gradlew assembleDebug        # build; output app/build/outputs/apk/debug/ (a copy is kept at ./app-debug.apk)
.\gradlew installDebug         # install on connected device/emulator
```

There are **no unit/instrumented tests** (`app/src/test` and `androidTest` don't exist) and no lint config; "validated" in `PROGRESS.md` means the debug APK compiled. Gradle config cache and build cache are enabled (`gradle.properties`).

Redeem-code tooling (Node, requires Firebase service credentials — see `tools/redeem/README.md`):

```
node tools/redeem/generate-code.js --days 30 --count 3 --note "..."   # print code + Firestore JSON
node tools/redeem/bulk-push.js   --until 2026-11-13 --count 3 --note "..."   # generate AND push to Firestore
```

## Architecture

### Data acquisition: no backend, everything is scraped/intercepted on-device
The app has no server. Live market data comes from **several parallel `WebView`s** in `MainActivity` that load Stockbit pages using the user's own logged-in session:
- `webView` (visible, manual orderbook), plus hidden `webViewMovers`, `webViewFreq`, `webViewVolume` (Top Value / Frequency / Volume) and `webViewStream`.
- Hidden WebViews **must stay 1280x720 with `alpha="0"` behind the main layout** — sizing them to 0dp or moving off-screen stops GPU rendering and Stockbit's React DOM never renders (documented bug fix; don't "optimize" this).
- JS is injected from `app/src/main/assets/` (`stockbit_injector.js`, `movers_injector.js`, `stream_injector.js`, `stream_probe.js`) and talks back through `@JavascriptInterface` bridges registered under the names `Android`, `AndroidStream`, `AndroidProbe` (`bridge/StockbitBridge.kt` and bridge classes in `MainActivity`). `stream_probe.js` hooks `WebSocket`/`EventSource`/`fetch`/`XHR` to capture streams (running trade, bandar detector from `exodus.stockbit.com/marketdetectors/{ticker}`).
- Scraping is DOM-selector based and fragile; changes to injectors need care (e.g. ticker validation uses unique-count, not input-count, because React leaves shadow autocomplete inputs).

Other sources: Yahoo Finance candles (`YahooFinanceRepository`, 5‑min candle cache, single-flight dedupe of identical in-flight requests via `ConcurrentHashMap<String, CompletableDeferred>`), `StockbitCandleRepository` (candles via the Stockbit session, wraps `yahooRepo`), and TradingView scanner (`TradingViewScreenerRepository`).

### Two independent pipelines — do not conflate
1. **Scalping pipeline (tabs Manual / Movers / Top Picks, real-time):** two separate flows, `processJsonData()` → `_manualFlow` and `processMoversJsonData()` → `_moversFlow` (keep them isolated; merging them caused manual stocks to leak into Movers). Per stock: `OrderBookRepository` (snapshots, delta volume; ignores lot jitter when `MarketSession` is CLOSED/PRE_OPEN/BREAK) → `OrderFlowAnalyzer` (Rupiah-value walls: offer ≥ Rp200jt, bid ≥ Rp500jt, fake wall, absorption, breakout) + `TechnicalAnalyzer` (VWAP, MFI, EMA9/21, BB, MACD, Supertrend, Fibonacci, 15m/5d candles) → `ScoringEngine` (0–100: orderflow 35 + technical 35 + session 20, R/R penalty, hysteresis anti-flicker, anti-pucuk hard block at ≥+7%, early-momentum bonus, panic bailout alarm). `BSJPScoringEngine` is a separate end-of-day (BSJP) variant.
2. **Screener pipeline (tab Screener, delayed data):** `TradingViewScreenerRepository` queries the IDX scanner (844 tickers) → `ScreenerScoringEngine` (indicator/structure-only scoring; 8 weighted components, weights per `TradingStyle` Daytrade vs Swing) → top 12 candidates (8 for search) get real candles and `SmartMoneyAnalyzer` (swings, ZigZag pivots, HH/HL, BOS/CHoCH, order blocks, two-leg Fibonacci). **TradingView data is 10 minutes delayed** — it is only for candidate filtering; entry prices/orderbook must come from Stockbit. Selecting a screener card hands the ticker to the Manual tab so it re-enters pipeline 1.
   - Verified scanner filter operators: `equal`, `greater`, `less`, `in_range` only (others → HTTP 400). Name filtering is case-sensitive/exact, so stock search is done client-side over a cached universe.
   - Candle analysis window is fixed at 100 candles regardless of timeframe; default 1D/3mo (needs ≥~60 candles for valid Fibonacci/swing structure).

### Cross-cutting rules
- All Support/Resistance/Target/Stop-loss prices must pass through `engine/PriceFraction.roundToValidTick` (IDX tick sizes 1/2/5/10/25).
- Technical indicators and S/R are recomputed from the latest `lastPrice` each cycle; only the Yahoo candles are cached (permanent caching of derived values caused wrong S/R).
- Fibonacci: premier leg (low→high, buy zone 0.5–0.618) and secondary leg (high→low, sell zone). High anchor = ZigZag pivot including the still-running extreme; low anchor = nearest higher low before the latest high.

### UI
`MainActivity` (~1500 lines, hosts all WebViews and pipeline wiring) + `ViewPager2` (`RankingPagerAdapter`) with tabs in this order: Manual, Movers, Top Picks, **Screener (index 3)**, **Portfolio (index 4)**. Adding a tab shifts indices. `DetailBottomSheet` shows per-stock detail and the AI opinion; `PortfolioFragment` / `PortfolioRescueBottomSheet` / `ExitTradeBottomSheet` handle positions.

### AI advisor
`AiConfig` (single source for Base URL / API key / model, in `SharedPreferences`) + `AiRepository` call any **OpenAI-compatible** endpoint (default a LAN 9Router at `http://<PC-IP>:20128/v1`; emulator uses `10.0.2.2`; key optional unless the URL is Groq Cloud). It walks a model fallback chain on 404/model-rejected. Legacy names remain in XML IDs (`tvGroqSummary`, `btnAskGroq`) on purpose — don't rename them.

### Login gate (redeem codes)
`LoginActivity` is the **launcher**; `MainActivity` runs `verifyStoredSession()` in `onCreate` and force-logs-out on expired/revoked codes. `data/auth/LoginRepository` validates against Firestore (`redeem_codes/{CODE}`, `activations`), binds one code to one device ID, and rejects backward clock changes (>2 min). Firebase is initialized **programmatically** from `LoginConfig` (no `google-services.json`); while `LoginConfig.PROJECT_ID` is empty login is skipped (dev mode). Setup/ops details: `SETUP_FIREBASE_LOGIN.md`, `CATATAN_KODE_REDEEM.md`.

## Documentation to keep in sync
`PROGRESS.md` (detailed design notes, measured findings, bug history) and `CHANGELOG.md` (versioned, newest first) are updated with each feature in this repo — consult them for the rationale behind non-obvious thresholds before changing scoring logic. `PANDUAN_PENGUJIAN_SENIN.md` is the live-market test checklist (features are still untested on a real device during market hours).

## Known gaps for swing trading (backlog, urut prioritas)
Hasil telaah awal berdasarkan `PROGRESS.md`/`CHANGELOG.md` (kode `ScreenerScoringEngine.kt` belum ditelaah baris per baris):
1. **Tidak ada trading plan swing** — tab Screener hanya menampilkan zona beli/jual Fibonacci; belum ada stop loss (ATR / swing low), risk/reward, ukuran posisi, trailing stop.
2. **Satu timeframe saja** — belum ada konfirmasi multi-timeframe (tren mingguan sebagai filter + harian untuk entry).
3. **Bandarmology di screener hanya proxy** — memakai `MoneyFlow` TradingView, bukan broker summary / net foreign multi-hari (data asli dari `exodus.stockbit.com/marketdetectors` hanya dipakai di jalur scalping).
4. **Tanpa konteks pasar/sektor** — belum ada tren IHSG, relative strength vs IHSG, rotasi sektor.
5. **Indikator belum ada** — ATR, ADX, OBV/A-D, support/resistance horizontal, pola candle, risiko gap.
6. **Filter khas IDX belum ada** — likuiditas masih volume (lot) bukan nilai Rupiah; belum ada papan pemantauan khusus/UMA, suspensi, aksi korporasi, batas ARA/ARB; fundamental dasar (PER/PBV/market cap) belum ada.
7. **Bobot belum divalidasi** — dipilih manual, diuji pada 6 emiten saja; tidak ada backtest/test otomatis.

Urutan kerja yang disarankan: (1) trading plan swing berbasis ATR + R/R → (2) filter tren mingguan → (4) filter IHSG → (7) backtest sederhana sebelum menambah/mengubah bobot.

## Repo hygiene
Root contains stray artifacts (`build_out.txt`, `c.txt`, `d.txt`, `_app-*.js`, `app-debug.apk`, `pdf_materi/`) that are not part of the build. `local.properties` and the Firebase keys in `LoginConfig.kt` are machine/project specific — don't overwrite them.
