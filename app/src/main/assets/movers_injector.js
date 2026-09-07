(function() {
    try {
        // Global caches agar data Top Movers & Top Frequency tersimpan antar siklus evaluasi
        window._cachedTopMovers = window._cachedTopMovers || [];
        window._cachedTopFreq = window._cachedTopFreq || [];
        window._moversActiveCategory = window._moversActiveCategory || 'MOVERS'; // 'MOVERS' atau 'FREQ'
        window._lastCategorySwitchTime = window._lastCategorySwitchTime || 0;

        var container = document.querySelector('#widget-container') || document;

        // === LANGKAH 1: Buka widget Movers jika belum terbuka (Anti-Toggle Loop) ===
        window._lastMoversBtnClick = window._lastMoversBtnClick || 0;
        var now = Date.now();

        var moversBtn = document.querySelector('button[data-cy="right-menu-movers"]');
        var isBtnActive = false;
        if (moversBtn) {
            var btnClass = (moversBtn.className || '').toLowerCase();
            var parentClass = (moversBtn.parentElement ? moversBtn.parentElement.className : '').toLowerCase();
            var aria = (moversBtn.getAttribute('aria-selected') || moversBtn.getAttribute('aria-expanded') || '').toLowerCase();
            isBtnActive = (btnClass.indexOf('active') >= 0 || parentClass.indexOf('active') >= 0 || aria === 'true');
        }

        var wContainer = document.querySelector('#widget-container');
        var isContainerVisible = wContainer && (wContainer.offsetWidth > 80 || (wContainer.getBoundingClientRect && wContainer.getBoundingClientRect().width > 80));

        // HANYA klik jika tombol belum aktif DAN container belum visible DAN sudah lewat cooldown 8 detik!
        if (!isBtnActive && !isContainerVisible && (now - window._lastMoversBtnClick > 8000)) {
            if (moversBtn) {
                moversBtn.click();
                window._lastMoversBtnClick = now;
                if (window.Android && window.Android.onMoversDebug) {
                    window.Android.onMoversDebug('Membuka panel Movers Stockbit (click triggered)...');
                }
            }
        }

        // Log elemen kontrol yang ada di dalam widget untuk diagnosa akurat
        var debugControls = [];
        var ctrlElements = container.querySelectorAll('button, [role="tab"], div[class*="tab"], select, option');
        ctrlElements.forEach(function(el) {
            var t = (el.innerText || el.textContent || el.value || '').trim();
            if (t.length > 0 && t.length < 30 && debugControls.indexOf(t) < 0) {
                debugControls.push(t);
            }
        });
        if (debugControls.length > 0 && !window._loggedMoversControls) {
            window._loggedMoversControls = true;
            if (window.Android && window.Android.onMoversDebug) {
                window.Android.onMoversDebug('Widget buttons: ' + debugControls.slice(0, 10).join(' | '));
            }
        }

        // === LANGKAH 2: Ganti Kategori antara Top Movers & Top Frequency ===
        var now = Date.now();
        if (now - window._lastCategorySwitchTime > 3000) {
            var nextCat = (window._moversActiveCategory === 'MOVERS') ? 'FREQ' : 'MOVERS';
            var switched = switchCategory(nextCat);
            if (switched) {
                window._moversActiveCategory = nextCat;
                window._lastCategorySwitchTime = now;
            }
        }

        // === LANGKAH 3: Tunggu render lalu scrape emiten yang tampil ===
        setTimeout(scrapeAndMergeMovers, 500);

    } catch(e) {
        if (window.Android && window.Android.onMoversDebug) {
            window.Android.onMoversDebug('Init error: ' + e.message);
        }
    }

    // Fungsi pencari tombol / tab / dropdown untuk Top Movers vs Top Frequency
    function switchCategory(targetCat) {
        var container = document.querySelector('#widget-container') || document;

        var keywords = (targetCat === 'FREQ')
            ? ['top frequency', 'top freq', 'frequency', 'freq', 'frekuensi']
            : ['top movers', 'top value', 'top turnover', 'turnover', 'movers', 'hot', 'value'];

        // 1. Cek semua elemen clickable (button, tab, div, span, li)
        var clickables = container.querySelectorAll('button, [role="tab"], div[class*="tab"], li, a, span, p');
        for (var i = 0; i < clickables.length; i++) {
            var el = clickables[i];
            var text = (el.innerText || el.textContent || '').trim().toLowerCase();

            // SANGAT PENTING: Jangan klik Top Volume, Top Gainer, Top Loser!
            if (text.indexOf('volume') >= 0 || text.indexOf('loser') >= 0 || text.indexOf('gainer') >= 0) continue;

            for (var k = 0; k < keywords.length; k++) {
                var kw = keywords[k];
                if (text === kw || (text.indexOf(kw) >= 0 && text.length <= kw.length + 8)) {
                    el.click();
                    return true;
                }
            }
        }

        // 2. Cek jika menggunakan dropdown <select>
        var selects = container.querySelectorAll('select');
        for (var s = 0; s < selects.length; s++) {
            var sel = selects[s];
            for (var o = 0; o < sel.options.length; o++) {
                var optText = (sel.options[o].text || '').toLowerCase();
                if (optText.indexOf('volume') >= 0 || optText.indexOf('loser') >= 0 || optText.indexOf('gainer') >= 0) continue;
                for (var k = 0; k < keywords.length; k++) {
                    if (optText.indexOf(keywords[k]) >= 0) {
                        if (sel.selectedIndex !== o) {
                            sel.selectedIndex = o;
                            sel.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                        return true;
                    }
                }
            }
        }

        return false;
    }

    function scrapeAndMergeMovers() {
        try {
            var currentScraped = [];
            var seen = {};

            var container = document.querySelector('#widget-container');
            var scope = container || document;

            // Scroll perlahan agar elemen virtual list terisi
            var scrollElem = container || document.querySelector('#widget-container') || document.querySelector('[class*="widget-container"]') || document.querySelector('tbody');
            if (scrollElem && scrollElem.scrollTop < 250) {
                scrollElem.scrollTop = 400;
            }

            // 1. Parser Utama: Baris Tabel langsung (tbody tr)
            var trRows = scope.querySelectorAll('#widget-container tbody tr, tbody tr');
            trRows.forEach(function(row) {
                var tds = row.querySelectorAll('td');
                if (tds.length < 2) return;

                var ticker = "";
                var img = tds[0].querySelector('img[src*="/logos/companies/"]');
                if (img) ticker = (img.getAttribute('alt') || '').trim().toUpperCase();
                if (!ticker || !/^[A-Z]{2,5}$/.test(ticker)) {
                    var link = tds[0].querySelector('a[href*="/symbol/"]');
                    if (link) {
                        var m = (link.getAttribute('href') || '').match(/\/symbol\/([A-Z]{2,5})/);
                        if (m) ticker = m[1];
                    }
                }
                if (!ticker || !/^[A-Z]{2,5}$/.test(ticker)) {
                    var mTxt = (tds[0].innerText || '').match(/\b([A-Z]{3,5})\b/);
                    if (mTxt) ticker = mTxt[1];
                }

                if (!ticker || seen[ticker]) return;

                var price = 0;
                var changePct = 0.0;
                var turnoverStr = "";

                // 1. Ekstrak dari cell td[1] secara spesifik (Kolom Harga di Stockbit)
                if (tds.length >= 2) {
                    var priceCellText = (tds[1].innerText || '').trim();
                    var pTokens = priceCellText.split(/\s+/);
                    for (var pt = 0; pt < pTokens.length; pt++) {
                        var pTok = pTokens[pt].trim();
                        // Reject jika token adalah perubahan harga (+/-), persentase, atau teks
                        if (pTok.startsWith('+') || pTok.startsWith('-') || pTok.includes('%') || pTok.startsWith('(') || /[a-zA-Z]/.test(pTok)) continue;
                        var pClean = pTok.replace(/[.,]/g, '');
                        var pVal = parseInt(pClean, 10);
                        if (!isNaN(pVal) && pVal >= 1 && pVal <= 99000) {
                            price = pVal;
                            break;
                        }
                    }
                }

                // 2. Ekstrak Change % dan Turnover dari seluruh baris
                for (var c = 1; c < tds.length; c++) {
                    var cText = (tds[c].innerText || '').trim();
                    var toMatch = cText.match(/(\d+[.,]?\d*)\s*([KMBTkmbt]|Jt|M|B|T)/);
                    if (toMatch && !turnoverStr) turnoverStr = toMatch[0];

                    var pMatch = cText.match(/([+-]?\d+[.,]\d+)%/);
                    if (pMatch && changePct === 0.0) changePct = parseFloat(pMatch[1].replace(',', '.'));

                    // Fallback jika tds[1] tidak berhasil menemukan harga
                    if (price === 0) {
                        var cTokens = cText.split(/\s+/);
                        for (var ct = 0; ct < cTokens.length; ct++) {
                            var curTok = cTokens[ct].trim();
                            if (curTok.startsWith('+') || curTok.startsWith('-') || curTok.includes('%') || curTok.startsWith('(') || /[a-zA-Z]/.test(curTok)) continue;
                            var cleanVal = curTok.replace(/[.,]/g, '');
                            var parsed = parseInt(cleanVal, 10);
                            if (!isNaN(parsed) && parsed >= 1 && parsed <= 99000) {
                                price = parsed;
                                break;
                            }
                        }
                    }
                }

                if (ticker && price > 0) {
                    seen[ticker] = true;
                    currentScraped.push({
                        ticker: ticker,
                        lastPrice: price,
                        changePercent: changePct,
                        turnover: turnoverStr,
                        source: (window._moversActiveCategory === 'FREQ') ? 'Top Frequency' : 'Top Movers'
                    });
                }
            });

            // 2. Parser Pendukung: Cari lewat elemen logo perusahaan
            var logoImgs = scope.querySelectorAll('img[src*="/logos/companies/"]');
            logoImgs.forEach(function(img) {
                var ticker = (img.getAttribute('alt') || '').trim().toUpperCase();
                if (!ticker || !/^[A-Z]{2,5}$/.test(ticker) || seen[ticker]) return;
                seen[ticker] = true;

                var price = 0;
                var changePct = 0;
                var turnoverStr = "";

                var row = img.closest('tr');
                if (row) {
                    var tds = row.querySelectorAll('td');
                    if (tds.length >= 2) {
                        for (var c = 1; c < tds.length; c++) {
                            var cellText = tds[c].innerText || '';
                            var valMatch = cellText.match(/(\d+[.,]?\d*)\s*([KMBTkmbt]|Jt|M|B|T)/);
                            if (valMatch && !turnoverStr) {
                                turnoverStr = valMatch[0];
                            }

                            var tokens = cellText.split(/\s+/);
                            for (var k = 0; k < tokens.length; k++) {
                                var t = tokens[k].trim();
                                if (t.startsWith('+') || t.startsWith('-') || t.includes('%') || t.startsWith('(') || /[a-zA-Z]/.test(t)) continue;
                                var clean = t.replace(/[.,]/g, '');
                                var val = parseInt(clean, 10);
                                if (!isNaN(val) && val >= 1 && val <= 99000 && price === 0) {
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
                            var t = tokens[i].trim();
                            if (t.startsWith('+') || t.startsWith('-') || t.includes('%') || t.startsWith('(') || /[a-zA-Z]/.test(t)) continue;
                            var clean = t.replace(/[.,]/g, '');
                            var val = parseInt(clean, 10);
                            if (!isNaN(val) && val >= 1 && val <= 99000 && price === 0) {
                                price = val;
                            }
                        }
                        var fallbackPctMatch = rowText.match(/([+-]?\d+[.,]\d+)%/);
                        if (fallbackPctMatch && changePct === 0) {
                            changePct = parseFloat(fallbackPctMatch[1].replace(',', '.'));
                        }
                    }
                }

                currentScraped.push({
                    ticker: ticker,
                    lastPrice: price,
                    changePercent: changePct,
                    turnover: turnoverStr,
                    source: (window._moversActiveCategory === 'FREQ') ? 'Top Frequency' : 'Top Movers'
                });
            });

            // Fallback link /symbol/
            if (currentScraped.length < 3) {
                var links = scope.querySelectorAll('a[href*="/symbol/"]');
                links.forEach(function(link) {
                    var href = link.getAttribute('href') || '';
                    var m = href.match(/\/symbol\/([A-Z]{2,5})/);
                    if (m && !seen[m[1]]) {
                        seen[m[1]] = true;
                        currentScraped.push({
                            ticker: m[1],
                            lastPrice: 0,
                            changePercent: 0,
                            turnover: "",
                            source: (window._moversActiveCategory === 'FREQ') ? 'Top Frequency' : 'Top Movers'
                        });
                    }
                });
            }

            // Simpan ke cache kategori masing-masing
            if (currentScraped.length > 0) {
                if (window._moversActiveCategory === 'FREQ') {
                    window._cachedTopFreq = currentScraped.slice(0, 30);
                } else {
                    window._cachedTopMovers = currentScraped.slice(0, 30);
                }

                // Sinkronkan harga terbaru ke cache kategori pasangan agar harga tidak bentrok/flicker
                currentScraped.forEach(function(fresh) {
                    var targetList = (window._moversActiveCategory === 'FREQ') ? window._cachedTopMovers : window._cachedTopFreq;
                    for (var t = 0; t < targetList.length; t++) {
                        if (targetList[t].ticker === fresh.ticker && fresh.lastPrice > 0) {
                            targetList[t].lastPrice = fresh.lastPrice;
                            targetList[t].changePercent = fresh.changePercent;
                        }
                    }
                });
            }

            // Gabungkan Top Frequency & Top Movers secara cerdas (Deduplikasi)
            var combined = [];
            var combinedSeen = {};

            // Prioritas 1: Masukkan Top Frequency (favorit scalper untuk kecepatan transaksi)
            for (var f = 0; f < window._cachedTopFreq.length; f++) {
                var itemF = window._cachedTopFreq[f];
                if (!combinedSeen[itemF.ticker]) {
                    combinedSeen[itemF.ticker] = true;
                    combined.push(itemF);
                }
            }

            // Prioritas 2: Masukkan Top Movers / Top Value (likuiditas turnover besar)
            for (var m = 0; m < window._cachedTopMovers.length; m++) {
                var itemM = window._cachedTopMovers[m];
                if (!combinedSeen[itemM.ticker]) {
                    combinedSeen[itemM.ticker] = true;
                    combined.push(itemM);
                }
            }

            // Fallback: Jika cache salah satu masih kosong di awal, kirim yang baru ter-scrape
            if (combined.length === 0 && currentScraped.length > 0) {
                combined = currentScraped;
            }

            // Ambil maksimal 45 emiten pilihan terbaik (hanya Top Movers + Top Frequency)
            var finalResults = combined.slice(0, 45);

            // Debug log ke Android
            if (window.Android && window.Android.onMoversDebug) {
                var s = finalResults.slice(0, 3).map(function(x) { return x.ticker + '=' + x.lastPrice + '(' + x.changePercent + '%)'; }).join(', ');
                window.Android.onMoversDebug(
                    'Movers [' + window._moversActiveCategory + ']: ' +
                    'Movers(' + window._cachedTopMovers.length + ') + Freq(' + window._cachedTopFreq.length + ') = ' +
                    finalResults.length + ' emiten. Sample: ' + s
                );
            }

            // Kirim ke Android
            if (finalResults.length > 0 && window.Android && window.Android.onMoversData) {
                window.Android.onMoversData(JSON.stringify(finalResults));
            }

        } catch(e) {
            if (window.Android && window.Android.onMoversDebug) {
                window.Android.onMoversDebug('Scrape error: ' + e.message);
            }
        }
    }
})();
