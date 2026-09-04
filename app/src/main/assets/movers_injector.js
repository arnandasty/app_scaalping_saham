(function() {
    try {
        // === LANGKAH 1: Klik tombol untuk buka panel Movers ===
        // Hanya klik jika belum terbuka (cek keberadaan logo emiten di movers)
        var isMoversOpen = document.querySelector('img[src*="/logos/companies/"]');
        if (!isMoversOpen) {
            var moversBtn = document.querySelector('button[data-cy="right-menu-movers"]');
            if (moversBtn) {
                moversBtn.click();
            }
        }

        // === LANGKAH 2: Tunggu panel render lalu scrape ===
        setTimeout(scrapeMovers, 800);

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
                // Struktur tabel Stockbit Movers biasanya: <td> berisi img + ticker, lalu kolom harga, change, dll.
                var row = img.closest('tr');
                if (row) {
                    var tds = row.querySelectorAll('td');
                    if (tds.length >= 2) {
                        // Iterasi dari td indeks 1 (mengabaikan kolom ticker di td 0)
                        for (var c = 1; c < tds.length; c++) {
                            var cellText = tds[c].innerText || '';
                            
                            // Split by whitespace untuk menangani sel yang berisi harga \n persentase
                            var tokens = cellText.split(/\s+/);
                            for (var k = 0; k < tokens.length; k++) {
                                var t = tokens[k];
                                var clean = t.replace(/[.,]/g, ''); // Hapus titik dan koma ribuan
                                var val = parseInt(clean, 10);
                                // Hanya ambil angka murni tanpa huruf, %, atau +
                                if (!isNaN(val) && val >= 50 && val <= 99000 && !/[a-zA-Z%+]/.test(t) && price === 0) {
                                    price = val;
                                }
                            }
                            
                            // Cari persentase di kolom mana pun
                            var pctMatch = cellText.match(/([+-]?\d+[.,]\d+)%/);
                            if (pctMatch && changePct === 0) {
                                changePct = parseFloat(pctMatch[1].replace(',', '.'));
                            }
                        }
                    }
                }
                
                // Fallback jika bukan <tr> (div layout)
                if (price === 0) {
                    row = img.closest('[class*="row"]') || img.parentElement;
                    if (row) {
                        var rowText = row.innerText || '';
                        // Cari angka tepat sebelum persentase, atau iterasi dengan aman
                        var tokens = rowText.split(/\s+/);
                        for (var i = 0; i < tokens.length; i++) {
                            var t = tokens[i];
                            var clean = t.replace(/[.,]/g, '');
                            var val = parseInt(clean, 10);
                            if (!isNaN(val) && val >= 50 && val <= 99000 && !/[a-zA-Z%+]/.test(t) && price === 0) {
                                price = val;
                            }
                        }
                        var fallbackPctMatch = rowText.match(/([+-]?\d+[.,]\d+)%/);
                        if (fallbackPctMatch && changePct === 0) {
                            changePct = parseFloat(fallbackPctMatch[1].replace(',', '.'));
                        }
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
