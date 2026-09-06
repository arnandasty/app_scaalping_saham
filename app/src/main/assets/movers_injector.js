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

            // Scroll kontainer secara berkala agar row lazy-loaded ikut ter-render
            var scrollElem = container || document.querySelector('#widget-container') || document.querySelector('[class*="widget-container"]') || document.querySelector('tbody');
            if (scrollElem && scrollElem.scrollTop < 300) {
                scrollElem.scrollTop = 500;
            }

            var logoImgs = scope.querySelectorAll('img[src*="/logos/companies/"]');
            logoImgs.forEach(function(img) {
                // Ambil ticker dari alt attribute (selalu berisi nama ticker)
                var ticker = (img.getAttribute('alt') || '').trim().toUpperCase();

                // Validasi format ticker IDX (2-5 huruf kapital)
                if (!ticker || !/^[A-Z]{2,5}$/.test(ticker) || seen[ticker]) return;
                seen[ticker] = true;

                var price = 0;
                var changePct = 0;
                var turnoverStr = "";

                // Cari data harga & turnover dari elemen saudara di baris yang sama
                var row = img.closest('tr');
                if (row) {
                    var tds = row.querySelectorAll('td');
                    if (tds.length >= 2) {
                        for (var c = 1; c < tds.length; c++) {
                            var cellText = tds[c].innerText || '';
                            
                            // Ambil turnover / value (misal: 45.2B, 12.8M, 950K)
                            var valMatch = cellText.match(/(\d+[.,]?\d*)\s*([KMBTkmbt]|Jt|M|B|T)/);
                            if (valMatch && !turnoverStr) {
                                turnoverStr = valMatch[0];
                            }

                            // Split by whitespace untuk menangani sel yang berisi harga \n persentase
                            var tokens = cellText.split(/\s+/);
                            for (var k = 0; k < tokens.length; k++) {
                                var t = tokens[k];
                                var clean = t.replace(/[.,]/g, '');
                                var val = parseInt(clean, 10);
                                if (!isNaN(val) && val >= 50 && val <= 99000 && !/[a-zA-Z%+]/.test(t) && price === 0) {
                                    price = val;
                                }
                            }
                            
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
                        var valMatchFallback = rowText.match(/(\d+[.,]?\d*)\s*([KMBTkmbt]|Jt|M|B|T)/);
                        if (valMatchFallback && !turnoverStr) {
                            turnoverStr = valMatchFallback[0];
                        }
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
                    changePercent: changePct,
                    turnover: turnoverStr
                });
            });

            // === FALLBACK: Cari dari link /symbol/ jika logo tidak ditemukan ===
            if (results.length < 5) {
                var links = scope.querySelectorAll('a[href*="/symbol/"]');
                links.forEach(function(link) {
                    var href = link.getAttribute('href') || '';
                    var m = href.match(/\/symbol\/([A-Z]{2,5})/);
                    if (m && !seen[m[1]]) {
                        seen[m[1]] = true;
                        results.push({ ticker: m[1], lastPrice: 0, changePercent: 0, turnover: "" });
                    }
                });
            }

            // Ambil hingga 50 emiten teratas agar pilihan pasar jauh lebih komprehensif
            var topResults = results.slice(0, 50);

            // Debug info
            if (window.Android && window.Android.onMoversDebug) {
                window.Android.onMoversDebug(
                    'Found ' + topResults.length + ' movers tickers. ' +
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
