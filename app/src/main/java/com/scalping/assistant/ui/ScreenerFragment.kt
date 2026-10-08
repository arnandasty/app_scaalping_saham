package com.scalping.assistant.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scalping.assistant.R
import com.scalping.assistant.data.repository.CandleTimeframe
import com.scalping.assistant.data.repository.ScoredScreenerStock
import com.scalping.assistant.data.repository.ScreenerPreset
import com.scalping.assistant.data.repository.TradingViewScreenerRepository
import com.scalping.assistant.data.repository.YahooFinanceRepository
import com.scalping.assistant.engine.TradingStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tab Screener — menyaring & MEMBERI SKOR kandidat dari seluruh emiten IDX.
 *
 * Alur dua tahap (hybrid):
 *   1. Snapshot cepat seluruh universe -> skor awal semua kandidat.
 *   2. Candle nyata untuk 20 teratas -> analisis SMC (Order Block, BOS, CHoCH,
 *      Fibonacci, struktur HH/HL) lalu skor dihitung ulang.
 *
 * Hasil selalu diurutkan dari skor tertinggi.
 *
 * Skor di sini BERBEDA dari ScoringEngine.kt: penilaian ini murni dari indikator
 * & struktur pasar, tidak melihat orderbook. Karena data delayed, kartu di sini
 * adalah KANDIDAT — menekannya akan membuka emiten di Stockbit untuk analisis real-time.
 */
class ScreenerFragment : Fragment() {

    companion object {
        /**
         * Jumlah kandidat teratas yang dianalisis dengan candle nyata.
         *
         * Dibuat konservatif (12) karena tiap kandidat = 1 request candle. Bila terlalu
         * banyak, pemakaian pertama kali (cache kosong) bisa membebani jaringan HP dan
         * memicu pembatasan dari Yahoo Finance.
         */
        private const val TIME_SAFE_DEEP_COUNT = 12

        /**
         * Timeframe yang boleh dipilih di tab Screener.
         *
         * Isinya sengaja dikumpulkan di satu tempat supaya chip TF dan default saat gaya
         * trading berganti selalu memakai sumber yang sama — tidak mungkin ada chip yang
         * tampil tetapi tidak punya default, atau sebaliknya.
         *
         * Semua pilihan memakai jendela analisis 100 candle terakhir (lihat
         * [CandleTimeframe.analysisWindow]), jadi level Fibonacci 15M dan 1D bisa dibandingkan
         * langsung: yang berbeda hanya satuan waktunya, bukan kedalaman riwayatnya.
         */
        private val SCREENER_TIMEFRAMES = listOf(
            CandleTimeframe.INTRADAY_BULAN1,  // 15M x 1 bulan (~640 candle)
            CandleTimeframe.BULAN3,           // 1D x 3 bulan (~67 candle) — default
            CandleTimeframe.BULAN6            // 1D x 6 bulan (~132 candle)
        )
    }

    private lateinit var rvScreener: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var tvEmptyMsg: TextView
    private lateinit var llStyles: LinearLayout
    private lateinit var llPresets: LinearLayout
    private lateinit var llTimeframes: LinearLayout
    private lateinit var pbLoading: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var tvSearchClear: TextView
    private lateinit var tvSearchHint: TextView

    private lateinit var adapter: ScreenerAdapter
    private var repo: TradingViewScreenerRepository? = null
    private val styleChips = mutableMapOf<TradingStyle, TextView>()
    private val presetChips = mutableMapOf<ScreenerPreset, TextView>()
    private val timeframeChips = mutableMapOf<CandleTimeframe, TextView>()

    /** Gaya trading aktif. Default SWING: sesuai kebutuhan swing 3-10 hari. */
    private var selectedStyle = TradingStyle.SWING
    private var selectedPreset = ScreenerPreset.forStyle(TradingStyle.SWING).first()
    private var selectedTimeframe = TradingStyle.SWING.defaultTimeframe
    private var isLoading = false

    /** Job debounce pencarian: dibatalkan saat ketikan berikutnya datang. */
    private var searchJob: Job? = null

    /** true bila daftar yang tampil berasal dari pencarian, bukan dari preset. */
    private var inSearchMode = false

    /**
     * Pengaman saat kolom pencarian dikosongkan OLEH KODE (bukan oleh pengguna),
     * supaya pengosongan itu tidak memicu pencarian/scan ulang yang tidak perlu.
     */
    private var suppressSearchWatcher = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_screener, container, false)

        rvScreener = view.findViewById(R.id.rvScreener)
        layoutEmpty = view.findViewById(R.id.layoutScreenerEmpty)
        tvEmptyMsg = view.findViewById(R.id.tvScreenerEmptyMsg)
        llStyles = view.findViewById(R.id.llScreenerStyles)
        llPresets = view.findViewById(R.id.llScreenerPresets)
        llTimeframes = view.findViewById(R.id.llScreenerTimeframes)
        pbLoading = view.findViewById(R.id.pbScreenerLoading)
        etSearch = view.findViewById(R.id.etScreenerSearch)
        tvSearchClear = view.findViewById(R.id.tvScreenerSearchClear)
        tvSearchHint = view.findViewById(R.id.tvScreenerSearchHint)

        // Klik kartu = buka rincian skor (transparan per komponen), bukan langsung pindah.
        adapter = ScreenerAdapter { item -> showScoreBreakdown(item) }
        rvScreener.layoutManager = LinearLayoutManager(requireContext())
        rvScreener.adapter = adapter

        // Pakai YahooFinanceRepository milik Activity agar cache candle dibagi —
        // emiten yang sudah pernah dianalisis pipeline utama tidak diunduh dua kali.
        val yahooRepo = (activity as? com.scalping.assistant.MainActivity)?.yahooRepo
            ?: YahooFinanceRepository()
        repo = TradingViewScreenerRepository(yahooRepo)

        buildStyleChips()
        buildPresetChips()
        buildTimeframeChips()
        setupSearch()
        showEmpty("Pilih preset di atas untuk menyaring & memberi skor pada 844 emiten IDX.")

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Muat preset awal otomatis agar tab tidak kosong saat pertama dibuka.
        if (adapter.itemCount == 0) runScan(selectedPreset)
    }

    // ============================================================
    // GAYA TRADING CHIPS
    // ============================================================

    /**
     * Membangun chip pemilih gaya trading.
     *
     * Gaya trading adalah pilihan PALING ATAS: ia menentukan bobot penilaian
     * (daytrade menekankan likuiditas & aliran dana, swing menekankan tren)
     * sekaligus preset penyaring dan timeframe default yang dipakai.
     */
    private fun buildStyleChips() {
        llStyles.removeAllViews()
        styleChips.clear()

        llStyles.addView(TextView(requireContext()).apply {
            text = "Gaya:"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, dp(8), 0)
        })

        for (style in TradingStyle.entries) {
            val chip = TextView(requireContext()).apply {
                text = style.label
                textSize = 11f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                gravity = Gravity.CENTER
                setOnClickListener { onStyleSelected(style) }
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }

            styleChips[style] = chip
            llStyles.addView(chip)
        }
        updateStyleChipStyles()
    }

    private fun updateStyleChipStyles() {
        for ((style, chip) in styleChips) {
            if (style == selectedStyle) {
                chip.setBackgroundResource(R.drawable.bg_chip_active_blue)
                chip.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip)
                chip.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        // Tagline gaya aktif ditampilkan di baris petunjuk agar pengguna tahu bedanya.
        showStyleHint()
    }

    /** Baris petunjuk default: menjelaskan bobot gaya trading yang sedang aktif. */
    private fun showStyleHint() {
        tvSearchHint.visibility = View.VISIBLE
        tvSearchHint.text = "Gaya ${selectedStyle.label}: ${selectedStyle.tagline} · " +
            "bobot Volume ${selectedStyle.wVolume} · Bandar ${selectedStyle.wBandar} · " +
            "MA ${selectedStyle.wMa} · Fib ${selectedStyle.wFib}"
    }

    /**
     * Ganti gaya trading: preset & timeframe default ikut berganti, lalu daftar
     * dimuat ulang. Cache repo memisahkan gaya, jadi hasil antar gaya tidak tertukar.
     */
    private fun onStyleSelected(style: TradingStyle) {
        if (style == selectedStyle) return
        selectedStyle = style
        exitSearchMode()
        selectedPreset = ScreenerPreset.forStyle(style).first()
        // Setiap gaya punya default TF sendiri; bila default-nya bukan salah satu pilihan
        // chip screener, jatuh ke TF default daftar (1D / 3 bulan) agar chip tetap sinkron.
        selectedTimeframe = style.defaultTimeframe.takeIf { it in SCREENER_TIMEFRAMES }
            ?: SCREENER_TIMEFRAMES.first { it.isDaily }
        updateStyleChipStyles()
        buildPresetChips()
        updateTimeframeChipStyles()
        runScan(selectedPreset, forceRefresh = true)
    }

    // ============================================================
    // PRESET CHIPS
    // ============================================================

    /** Chip preset dibangun ulang tiap gaya berganti: tiap gaya punya preset sendiri. */
    private fun buildPresetChips() {
        llPresets.removeAllViews()
        presetChips.clear()

        for (preset in ScreenerPreset.forStyle(selectedStyle)) {
            val chip = TextView(requireContext()).apply {
                text = preset.label
                textSize = 11f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                gravity = Gravity.CENTER
                setOnClickListener { runScan(preset) }
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }

            presetChips[preset] = chip
            llPresets.addView(chip)
        }
        updateChipStyles()
    }

    private fun updateChipStyles() {
        for ((preset, chip) in presetChips) {
            if (preset == selectedPreset) {
                chip.setBackgroundResource(R.drawable.bg_chip_active_blue)
                chip.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip)
                chip.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    // ============================================================
    // TIMEFRAME CHIPS
    // ============================================================

    /**
     * Membangun chip pemilih timeframe candle.
     *
     * Tab Screener bisa dibaca dari candle HARIAN (1D) maupun INTRADAY (15M). Pilihan
     * intraday ditambahkan karena pengujian menunjukkan sebagian emiten lebih akurat dinilai
     * dari gerak 15 menit — misalnya saat harga berbalik arah dalam beberapa jam sehingga
     * tarikan Fibonacci harian sudah kedaluwarsa.
     *
     * Yang membedakan antar chip hanyalah TIMEFRAME dan PANJANG RIWAYAT, bukan kedalaman
     * analisis: semuanya memakai jendela 100 candle terakhir.
     */
    private fun buildTimeframeChips() {
        llTimeframes.removeAllViews()
        timeframeChips.clear()

        llTimeframes.addView(TextView(requireContext()).apply {
            text = "TF:"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, dp(8), 0)
        })

        for (tf in SCREENER_TIMEFRAMES) {
            val chip = TextView(requireContext()).apply {
                text = "${tf.label} · ${tf.horizon}"
                textSize = 11f
                setPadding(dp(12), dp(5), dp(12), dp(5))
                gravity = Gravity.CENTER
                setOnClickListener { onTimeframeSelected(tf) }
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }

            timeframeChips[tf] = chip
            llTimeframes.addView(chip)
        }
        updateTimeframeChipStyles()
    }

    private fun updateTimeframeChipStyles() {
        for ((tf, chip) in timeframeChips) {
            if (tf == selectedTimeframe) {
                chip.setBackgroundResource(R.drawable.bg_chip_active_blue)
                chip.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip)
                chip.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    /** Ganti timeframe lalu muat ulang analisis memakai candle timeframe tersebut. */
    private fun onTimeframeSelected(tf: CandleTimeframe) {
        if (tf == selectedTimeframe) return
        selectedTimeframe = tf
        updateTimeframeChipStyles()
        // Mengganti timeframe juga membatalkan mode pencarian supaya daftar
        // kembali sinkron dengan chip preset yang aktif.
        exitSearchMode()
        runScan(selectedPreset, forceRefresh = true)
    }

    // ============================================================
    // SCAN + SKOR
    // ============================================================

    private fun runScan(preset: ScreenerPreset, forceRefresh: Boolean = false) {
        if (isLoading) return

        exitSearchMode()
        selectedPreset = preset
        updateChipStyles()

        isLoading = true
        pbLoading.visibility = View.VISIBLE
        tvEmptyMsg.text = "Menyaring & memberi skor (${preset.label}) · gaya ${selectedStyle.label}...\n" +
            "Mengunduh candle ${selectedTimeframe.label} (${selectedTimeframe.horizon}) untuk " +
            "$TIME_SAFE_DEEP_COUNT kandidat teratas."
        layoutEmpty.visibility = View.VISIBLE
        rvScreener.visibility = View.GONE

        lifecycleScope.launch {
            val hasil = repo?.scan(
                preset,
                limit = 60,
                deepAnalysisCount = TIME_SAFE_DEEP_COUNT,
                forceRefresh = forceRefresh,
                timeframe = selectedTimeframe,
                style = selectedStyle
            ) ?: emptyList()

            pbLoading.visibility = View.GONE
            isLoading = false

            if (!isAdded) return@launch

            if (hasil.isEmpty()) {
                showEmpty(
                    "Tidak ada emiten yang lolos filter \"${preset.label}\" pada TF ${selectedTimeframe.label}.\n" +
                        "Coba preset atau timeframe lain, atau periksa koneksi internet."
                )
            } else {
                adapter.submitList(hasil)
                rvScreener.visibility = View.VISIBLE
                layoutEmpty.visibility = View.GONE
            }
        }
    }

    private fun showEmpty(message: String) {
        tvEmptyMsg.text = message
        layoutEmpty.visibility = View.VISIBLE
        rvScreener.visibility = View.GONE
    }

    // ============================================================
    // PENCARIAN KODE / NAMA SAHAM
    // ============================================================

    /**
     * Menyiapkan kolom pencarian.
     *
     * Pencarian bekerja DI SISI HP atas daftar 844 emiten IDX yang diunduh sekali
     * (~268 KB, ~0,8 detik) lalu disimpan di memori. Jadi mengetik terasa instan
     * dan tidak membebani jaringan. Debounce 300 ms mencegah pencarian dijalankan
     * pada tiap huruf ketika pengguna mengetik cepat.
     */
    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchWatcher) return
                val query = s?.toString()?.trim().orEmpty()
                tvSearchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE

                searchJob?.cancel()
                if (query.isEmpty()) {
                    // Kolom dikosongkan -> kembali ke daftar hasil screening preset.
                    exitSearchMode()
                    if (adapter.itemCount == 0) runScan(selectedPreset)
                    return
                }

                // Kode saham IDX selalu 4 huruf kapital; jaga agar input tetap bersih.
                searchJob = lifecycleScope.launch {
                    delay(300)
                    runSearch(query)
                }
            }
        })

        etSearch.setOnEditorActionListener { _, _, _ ->
            val query = etSearch.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { runSearch(query) }
            }
            true
        }

        tvSearchClear.setOnClickListener {
            clearSearchBox()
            etSearch.clearFocus()
        }
    }

    /** Mengosongkan kolom pencarian tanpa memicu watcher-nya. */
    private fun clearSearchBox() {
        suppressSearchWatcher = true
        etSearch.setText("")
        tvSearchClear.visibility = View.GONE
        suppressSearchWatcher = false
    }

    /**
     * Mencari emiten lalu LANGSUNG menilainya dengan bobot gaya trading aktif.
     *
     * Berbeda dari [runScan], pencarian sengaja melewati filter preset: tujuan di
     * sini menganalisis emiten yang dipilih pengguna, bukan menyaring universe.
     */
    private fun runSearch(query: String) {
        val repository = repo ?: return

        inSearchMode = true
        pbLoading.visibility = View.VISIBLE
        tvSearchHint.visibility = View.VISIBLE
        tvSearchHint.text = "Mencari \"$query\" & menilai dengan gaya ${selectedStyle.label} (TF ${selectedTimeframe.label})..."
        // Keterangan ini penting: pencarian = 1 unduhan daftar emiten + maksimal 8
        // analisis candle, jadi pengguna tahu kenapa perlu menunggu sejenak.
        tvEmptyMsg.text = "Mencari \"$query\"...\n" +
            "Menilai 8 kandidat teratas dengan candle ${selectedTimeframe.label} (${selectedTimeframe.horizon})."
        layoutEmpty.visibility = View.VISIBLE
        rvScreener.visibility = View.GONE

        lifecycleScope.launch {
            val ketemu = repository.searchStocks(query, limit = 30)
            val hasil = if (ketemu.isEmpty()) emptyList() else {
                repository.scoreStocks(
                    ketemu,
                    style = selectedStyle,
                    timeframe = selectedTimeframe,
                    deepAnalysis = true,
                    deepLimit = 8
                )
            }

            pbLoading.visibility = View.GONE
            if (!isAdded) return@launch
            // Hasil dari pencarian lama tidak boleh menimpa pencarian terbaru.
            if (!inSearchMode) return@launch

            if (hasil.isEmpty()) {
                showEmpty(
                    "Tidak ada saham yang cocok dengan \"$query\".\n" +
                        "Coba kode lain (mis. BBCA) atau potongan nama perusahaan (mis. \"bank\")."
                )
                tvSearchHint.text = "Tidak ada hasil untuk \"$query\" · gaya ${selectedStyle.label}"
            } else {
                adapter.submitList(hasil)
                rvScreener.visibility = View.VISIBLE
                layoutEmpty.visibility = View.GONE
                tvSearchHint.text = "Hasil pencarian \"$query\" · ${hasil.size} emiten · " +
                    "dinilai dengan gaya ${selectedStyle.label} (TF ${selectedTimeframe.label}). " +
                    "Kosongkan kolom untuk kembali ke hasil screening."
            }
        }
    }

    /** Menandai bahwa daftar yang tampil kembali berasal dari preset, bukan pencarian. */
    private fun exitSearchMode() {
        searchJob?.cancel()
        // Kolom dikosongkan agar tidak ada sisa kata kunci saat daftar sudah kembali
        // menampilkan hasil screening preset.
        if (etSearch.text?.isNotEmpty() == true) clearSearchBox()
        inSearchMode = false
        showStyleHint()
    }

    /**
     * Membuka emiten terpilih di orderbook Stockbit lalu meminta data Bandar Detector.
     * Analisis real-time (orderbook + bandar asli) tetap dihitung oleh pipeline lama.
     */
    private fun onStockSelected(item: ScoredScreenerStock) {
        val activity = activity as? com.scalping.assistant.MainActivity ?: return
        activity.openTickerFromScreener(item.ticker)
        activity.requestBandarDetector(item.ticker, force = true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ============================================================
    // RINCIAN SKOR
    // ============================================================

    /**
     * Menampilkan rincian skor tiap komponen beserta alasannya.
     *
     * Tujuannya agar penilaian bisa DIPERIKSA, bukan hanya dipercaya: pengguna
     * melihat sendiri komponen mana yang memberi/menghilangkan poin.
     */
    private fun showScoreBreakdown(item: ScoredScreenerStock) {
        val ctx = context ?: return
        val stock = item.stock
        val score = item.score

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dialog_sheet)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }

        container.addView(TextView(ctx).apply {
            text = "Rincian Skor — ${stock.ticker}"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
        })

        container.addView(TextView(ctx).apply {
            text = "${score.gradeEmoji}  ${score.total} / 100  ·  ${score.grade}"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(scoreColorHex(score.total)))
            setPadding(0, dp(2), 0, 0)
        })

        container.addView(TextView(ctx).apply {
            val depth = if (item.deepAnalyzed) "analisis candle penuh (SMC)" else "snapshot saja (komponen candle belum dihitung)"
            val company = stock.companyName.ifEmpty { stock.name }
            text = "$company · ${stock.sector.ifEmpty { "—" }}\nGaya ${score.style.label} · TF ${score.timeframe.label} · $depth"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(3), 0, dp(8))
        })

        // Satu baris per komponen: nama (kiri), poin (kanan), alasan (bawah, kecil).
        for (c in score.components) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(ctx).apply {
                text = c.name
                textSize = 13f
                setTextColor(Color.parseColor("#E2E8F0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(ctx).apply {
                text = "${c.earned}/${c.max}"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(componentColorHex(c.ratio)))
            })
            row.addView(top)
            row.addView(TextView(ctx).apply {
                text = c.note
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(2), 0, 0)
            })
            container.addView(row)
        }

        // Level Fibonacci dua tarikan, ditampilkan agar bisa dicocokkan dengan grafik.
        score.fibonacci?.premierLeg?.let { premier ->
            container.addView(sectionTitle(ctx, "Fibonacci — Tarikan 1 (Zona Beli) · TF ${score.timeframe.label} (${score.timeframe.horizon})"))
            container.addView(infoLine(ctx, "Leg: low ${fmtPrice(premier.startPrice)} → high ${fmtPrice(premier.endPrice)}"))
            container.addView(infoLine(ctx, "0,500 = ${fmtPrice(premier.level05)}   ·   0,618 = ${fmtPrice(premier.level0618)}"))
            container.addView(infoLine(ctx, "0,850 = ${fmtPrice(premier.level085)}   ·   1,618 (ekstensi) = ${fmtPrice(premier.extension1618)}"))
            container.addView(infoLine(ctx, "Zona beli 0,5-0,618 = ${fmtPrice(premier.zoneHigh)} – ${fmtPrice(premier.zoneLow)}"))
            container.addView(infoLine(ctx, "Jendela analisis: ${score.timeframe.analysisWindow} candle terakhir (${score.timeframe.interval} × ${score.timeframe.range})"))

            score.fibonacci?.secondaryLeg?.let { secondary ->
                val provisional = score.fibonacci.secondaryLegProvisional
                container.addView(sectionTitle(ctx,
                    if (provisional) "Fibonacci — Tarikan 2 (Proyeksi Target · pola sekunder belum terbentuk)"
                    else "Fibonacci — Tarikan 2 (Zona Jual)"))
                container.addView(infoLine(ctx, "Leg: high ${fmtPrice(secondary.startPrice)} → low ${fmtPrice(secondary.endPrice)}"))
                container.addView(infoLine(ctx,
                    if (provisional) "Proyeksi target 0,5-0,618 = ${fmtPrice(secondary.zoneLow)} – ${fmtPrice(secondary.zoneHigh)}"
                    else "Zona jual 0,5-0,618 = ${fmtPrice(secondary.zoneLow)} – ${fmtPrice(secondary.zoneHigh)}"))
            }

            container.addView(TextView(ctx).apply {
                text = "Status: ${score.fibonacci.state.label}"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, dp(6), 0, 0)
            })
        }

        container.addView(TextView(ctx).apply {
            text = "⚠️ Data TradingView delayed ± 10 menit. Skor ini untuk MENYARING kandidat, " +
                "bukan pemicu entry. Harga & orderbook real-time tetap dari Stockbit."
            textSize = 11f
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(0, dp(12), 0, 0)
        })

        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        btnRow.addView(actionButton(ctx, "Buka di Stockbit", R.drawable.bg_chip_active_blue, "#FFFFFF") {
            onStockSelected(item)
            dialog.dismiss()
        })
        btnRow.addView(actionButton(ctx, "Tutup", R.drawable.bg_chip, "#94A3B8") {
            dialog.dismiss()
        })
        container.addView(btnRow)

        dialog.setContentView(ScrollView(ctx).apply { addView(container) })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** Tombol teks berlatarkan drawable, lebar dibagi rata di dalam baris tombol. */
    private fun actionButton(
        ctx: android.content.Context,
        label: String,
        background: Int,
        textColor: String,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(textColor))
        setBackgroundResource(background)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    /** Judul bagian kecil di dalam dialog rincian. */
    private fun sectionTitle(ctx: android.content.Context, label: String): TextView = TextView(ctx).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#38BDF8"))
        setPadding(0, dp(12), 0, dp(3))
    }

    /** Baris informasi abu-abu untuk level/leg Fibonacci. */
    private fun infoLine(ctx: android.content.Context, label: String): TextView = TextView(ctx).apply {
        text = label
        textSize = 12f
        setTextColor(Color.parseColor("#94A3B8"))
        setPadding(0, dp(1), 0, dp(1))
    }

    /** Format harga gaya Rupiah: ribuan pakai titik, tanpa desimal. */
    private fun fmtPrice(value: Double): String =
        if (value >= 100) String.format("%,.0f", value).replace(',', '.')
        else String.format("%.1f", value)

    private fun scoreColorHex(total: Int): String = when {
        total >= 80 -> "#10B981"
        total >= 65 -> "#34D399"
        total >= 50 -> "#F59E0B"
        total >= 35 -> "#FB923C"
        else -> "#EF4444"
    }

    private fun componentColorHex(ratio: Double): String = when {
        ratio >= 0.8 -> "#10B981"
        ratio >= 0.5 -> "#34D399"
        ratio >= 0.25 -> "#F59E0B"
        ratio > 0.0 -> "#FB923C"
        else -> "#EF4444"
    }
}
