(function() {
    try {
        // === LANGKAH 1: Klik tombol untuk buka panel Movers ===
        var moversBtn = document.querySelector('button[data-cy="right-menu-movers"]');
        if (moversBtn) {
            moversBtn.click();
        }

        // === LANGKAH 2: Tunggu panel render lalu scrape ===
        setTimeout(scrapeMovers, 600);

    } catch(e) {
        if (window.Android && window.Android.onMoversDebug) {
            window.Android.onMoversDebug('Init error: ' + e.message);
        }
    }

    function scrapeMovers() {
        try {
            var results = [];
            var seen = {};

            // === STRATEGI UTAMA: Ambil ticker dari logo perusahaan ===
            // src = ".../logos/companies/VRNA.png", alt = "VRNA"
            // Ini adalah selector paling stabil — tidak bergantung pada CSS class
            var container = document.querySelector('#widget-container');
            var scope = container || document;

            var logoImgs = scope.querySelectorAll('img[src*="/logos/companies/"]');
            logoImgs.forEach(function(img) {
                // Ambil ticker dari alt attribute (selalu berisi nama ticker)
                var ticker = (img.getAttribute('alt') || '').trim().toUpperCase();

                // Validasi format ticker IDX (2-5 huruf kapital)
                if (!ticker || !/^[A-Z]{2,5}$/.test(ticker) || seen[ticker]) return;
                seen[ticker] = true;

                var price = 0;
                var changePct = 0;

                // Cari data harga dari elemen saudara di baris yang sama
                // Struktur: <td> berisi img + span ticker, kolom berikutnya berisi harga
                var row = img.closest('tr') || img.closest('[class*="row"]') || img.parentElement;
                if (row) {
                    var rowText = row.innerText || '';
                    // Cari semua angka dalam baris
                    var nums = rowText.match(/[\d,.]+/g) || [];
                    for (var n = 0; n < nums.length; n++) {
                        var val = parseFloat(nums[n].replace(/,/g, ''));
                        if (val >= 50 && val <= 99000 && price === 0) {
                            price = Math.round(val);
                        }
                    }
                    // Cari persentase (format: +16,00% atau -3,50%)
                    var pctMatch = rowText.match(/([+-]?\d+[.,]\d+)%/);
                    if (pctMatch) {
                        changePct = parseFloat(pctMatch[1].replace(',', '.'));
                    }
                }

                results.push({
                    ticker: ticker,
                    lastPrice: price,
                    changePercent: changePct
                });
            });

            // === FALLBACK: Cari dari link /symbol/ jika logo tidak ditemukan ===
            if (results.length < 2) {
                var links = scope.querySelectorAll('a[href*="/symbol/"]');
                links.forEach(function(link) {
                    var href = link.getAttribute('href') || '';
                    var m = href.match(/\/symbol\/([A-Z]{2,5})/);
                    if (m && !seen[m[1]]) {
                        seen[m[1]] = true;
                        results.push({ ticker: m[1], lastPrice: 0, changePercent: 0 });
                    }
                });
            }

            var topResults = results.slice(0, 15);

            // Debug info
            if (window.Android && window.Android.onMoversDebug) {
                window.Android.onMoversDebug(
                    'Found ' + topResults.length + ' tickers via logos. ' +
                    'Container: ' + (container ? 'YES' : 'NO (global)') + '. ' +
                    'URL: ' + window.location.pathname
                );
            }

            // Kirim ke Android
            if (topResults.length > 0 && window.Android && window.Android.onMoversData) {
                window.Android.onMoversData(JSON.stringify(topResults));
            }

        } catch(e) {
            if (window.Android && window.Android.onMoversDebug) {
                window.Android.onMoversDebug('Scrape error: ' + e.message);
            }
        }
    }
})();
