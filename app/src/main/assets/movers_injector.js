(function() {
    try {
        // === LANGKAH 1: Pastikan Panel Movers terbuka ===
        // Cari tombol movers di kanan layar
        var moversBtn = document.querySelector('button[data-cy="right-menu-movers"]');
        if (moversBtn) {
            // Klik tombol untuk buka/pastikan panel terbuka
            moversBtn.click();
        }

        // === LANGKAH 2: Tunggu panel terbuka lalu scrape ===
        setTimeout(function() {
            scrapeMovers();
        }, 800);

    } catch(e) {
        if (window.Android && window.Android.onMoversDebug) {
            window.Android.onMoversDebug('Init error: ' + e.message);
        }
    }

    function scrapeMovers() {
        try {
            var results = [];
            var seen = {};

            // === STRATEGI 1: Cari di dalam widget-container ===
            // Panel movers ada di div#widget-container
            var widgetContainer = document.querySelector('#widget-container');
            if (widgetContainer) {
                // Cari semua elemen teks yang berisi ticker (3-5 huruf kapital)
                var allElements = widgetContainer.querySelectorAll('span, div, td, p, strong, b');
                allElements.forEach(function(el) {
                    var text = (el.innerText || el.textContent || '').trim();
                    // Ticker = 2-5 huruf kapital saja (tidak boleh ada spasi atau angka)
                    if (/^[A-Z]{2,5}$/.test(text) && !seen[text]) {
                        // Blacklist kata umum bukan ticker
                        var skip = ['TOP','ALL','BUY','LOT','VAL','VOL','SELL','EDIT','LOAD',
                                    'MORE','MENU','SAVE','LIKE','NEXT','BACK','HOME','LIVE',
                                    'OPEN','HIGH','PREV','FREQ','GAIN','LOSS','NICE'];
                        if (skip.indexOf(text) === -1) {
                            seen[text] = true;

                            // Coba ambil harga dari elemen saudara
                            var parent = el.parentElement;
                            var price = 0;
                            var changePct = 0;

                            if (parent) {
                                // Cari angka yang bisa jadi harga
                                var siblings = parent.querySelectorAll('span, div');
                                siblings.forEach(function(sib) {
                                    var t = (sib.innerText || '').replace(/[^\d.,\-+%]/g, '').trim();
                                    var n = parseFloat(t.replace(',', ''));
                                    if (!isNaN(n) && n >= 50 && n <= 99000 && price === 0) {
                                        price = Math.round(n);
                                    }
                                    // Cari persentase
                                    if (t.includes('%')) {
                                        var pct = parseFloat(t.replace('%', '').replace(',', '.'));
                                        if (!isNaN(pct) && Math.abs(pct) < 50) {
                                            changePct = pct;
                                        }
                                    }
                                });
                            }

                            results.push({
                                ticker: text,
                                lastPrice: price,
                                changePercent: changePct
                            });
                        }
                    }
                });
            }

            // === STRATEGI 2: Fallback — cari link /symbol/ di seluruh halaman ===
            if (results.length < 3) {
                var links = document.querySelectorAll('a[href*="/symbol/"]');
                links.forEach(function(link) {
                    var href = link.getAttribute('href') || '';
                    var match = href.match(/\/symbol\/([A-Z]{2,5})/);
                    if (match && !seen[match[1]]) {
                        seen[match[1]] = true;
                        results.push({
                            ticker: match[1],
                            lastPrice: 0,
                            changePercent: 0
                        });
                    }
                });
            }

            // Ambil max 15 ticker teratas
            var topResults = results.slice(0, 15);

            // Kirim ke Android
            if (window.Android && window.Android.onMoversData) {
                window.Android.onMoversData(JSON.stringify(topResults));
            }

            // Debug
            if (window.Android && window.Android.onMoversDebug) {
                window.Android.onMoversDebug(
                    'Found ' + topResults.length + ' tickers. ' +
                    'Container: ' + (document.querySelector('#widget-container') ? 'YES' : 'NO') + '. ' +
                    'URL: ' + window.location.pathname
                );
            }

        } catch(e) {
            if (window.Android && window.Android.onMoversDebug) {
                window.Android.onMoversDebug('Scrape error: ' + e.message);
            }
        }
    }
})();
