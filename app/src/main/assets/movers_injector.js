(function() {
    try {
        // Sidebar Movers di Stockbit Desktop biasanya berisi tabel/daftar
        // dengan link ke halaman saham: <a href="/symbol/BBCA">
        // Kita cari semua elemen link yang mengarah ke simbol saham
        
        var results = [];
        var processedTickers = {};
        
        // Strategi 1: Cari link <a> dengan href berisi /symbol/
        var symbolLinks = document.querySelectorAll('a[href*="/symbol/"]');
        
        symbolLinks.forEach(function(link) {
            var href = link.getAttribute('href') || '';
            var match = href.match(/\/symbol\/([A-Z]{3,5})/);
            if (match) {
                var ticker = match[1];
                if (!processedTickers[ticker]) {
                    processedTickers[ticker] = true;
                    
                    // Cari harga & perubahan dari parent container link
                    var parent = link.closest('tr') || link.closest('div') || link.parentElement;
                    var text = parent ? parent.innerText : '';
                    var nums = text.match(/[\d.,]+/g) || [];
                    
                    var lastPrice = 0;
                    var changePercent = 0;
                    
                    // Cari angka yang masuk akal sebagai harga saham (50 - 99000)
                    for (var i = 0; i < nums.length; i++) {
                        var n = parseFloat(nums[i].replace(/,/g, ''));
                        if (n >= 50 && n <= 99000 && lastPrice === 0) {
                            lastPrice = Math.round(n);
                        }
                        // Cari persentase (biasanya ada tanda % atau angka kecil negatif/positif)
                        if (nums[i].indexOf('.') >= 0 && n > -50 && n < 50 && n !== 0) {
                            changePercent = n;
                        }
                    }
                    
                    if (lastPrice > 0) {
                        results.push({
                            ticker: ticker,
                            lastPrice: lastPrice,
                            changePercent: changePercent
                        });
                    }
                }
            }
        });
        
        // Strategi 2 (Fallback): Cari semua teks 4 huruf kapital di sidebar/panel kanan
        if (results.length === 0) {
            var allDivs = document.querySelectorAll('div, span, td');
            allDivs.forEach(function(el) {
                var text = (el.innerText || '').trim();
                if (/^[A-Z]{4}$/.test(text) && !processedTickers[text]) {
                    // Cek apakah ini bukan kata umum
                    var skipWords = ['OPEN','HIGH','PREV','FREQ','SELL','EDIT','DONE','MENU','NEXT','BACK','HOME','LIVE','NEWS','CHAT','POST','HELP','LOAD','SAVE','SORT','SEND','COPY','MOVE','FREE','LOCK','PLAY','STOP','LOSS','GAIN'];
                    if (skipWords.indexOf(text) === -1) {
                        processedTickers[text] = true;
                        results.push({
                            ticker: text,
                            lastPrice: 0,
                            changePercent: 0
                        });
                    }
                }
            });
        }
        
        // Kirim ke Android
        var topResults = results.slice(0, 15); // Ambil 15 teratas
        
        if (window.Android && window.Android.onMoversData) {
            window.Android.onMoversData(JSON.stringify(topResults));
        }
        
        // Debug log
        if (window.Android && window.Android.onMoversDebug) {
            window.Android.onMoversDebug(
                "Found " + topResults.length + " movers. " +
                "Links: " + symbolLinks.length + ". " +
                "URL: " + window.location.pathname
            );
        }
        
    } catch (e) {
        if (window.Android && window.Android.onMoversDebug) {
            window.Android.onMoversDebug("Error: " + e.message);
        }
    }
})();
