window.autoFillTickers = function(tickers) {
    if (!tickers || tickers.length === 0) return;
    const inputs = document.querySelectorAll('input');
    let tickerIndex = 0;
    inputs.forEach(input => {
        // Cari input yang masih kosong (belum ada tickernya)
        if (!input.value || input.value.trim() === '') {
            if (tickerIndex < tickers.length) {
                // Bypass React input setter protection
                let nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                if (nativeInputValueSetter) {
                    nativeInputValueSetter.call(input, tickers[tickerIndex]);
                } else {
                    input.value = tickers[tickerIndex];
                }
                
                // Trigger events so React recognizes the change
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                
                tickerIndex++;
            }
        }
    });
};

(function() {
    try {
        // Guard: halaman login
        if (window.location.pathname.includes('/login') || document.querySelector('input[type="password"]')) {
            if (window.Android && window.Android.onLoginRequired) window.Android.onLoginRequired();
            return;
        }

        // Helper: parse angka "5,627" atau "5.627" → 5627
        function parseNum(str) {
            if (!str) return 0;
            return parseInt(str.toString().replace(/[^0-9]/g, ''), 10) || 0;
        }

        function parsePercent(str) {
            if (!str) return 0.0;
            var m = str.toString().match(/([+-]?\d+[.,]?\d*)/);
            return m ? parseFloat(m[1].replace(',', '.')) : 0.0;
        }

        var SKIP_WORDS = ['FREQ','PREV','HIGH','OPEN','LOW','BELI','JUAL','IHSG',
            'SELL','NONE','VOID','INFO','LOAD','LIST','COPY','SORT','EDIT','VIEW',
            'CARI','NAMA','KODE','HARI','BULAN','YANG','DARI','DEAL','DONE','SAVE',
            'FREE','PLUS','TRUE','BEST','CALL','HOLD','STOP','NEXT','BACK','MENU',
            'MOVE','BULL','BEAR','AUTO','LIVE'];

        function isSkipWord(w) {
            return SKIP_WORDS.indexOf(w) >= 0;
        }

        var results = [];
        var processedTickers = {};

        // ================================================================
        // STRATEGI: Gunakan #main-container sebagai root
        // Di dalamnya ada div[direction="row"] yang berisi widget-widget saham
        // Setiap child dari row container itu = 1 widget orderbook saham
        // ================================================================
        var mainContainer = document.getElementById('main-container');

        if (!mainContainer) {
            if (window.Android && window.Android.onScrapingStatus) {
                window.Android.onScrapingStatus("main-container NOT found. Path: " + window.location.pathname);
            }
            return;
        }

        // Cari div[direction="row"] di dalam main-container (container horizontal scroll)
        var rowContainers = mainContainer.querySelectorAll('div[direction="row"]');
        var orderbookRow = null;

        for (var rc = 0; rc < rowContainers.length; rc++) {
            var row = rowContainers[rc];
            // Row yang benar punya minimal 1 child yang berisi teks "Bid" dan "Offer"
            var rowText = (row.innerText || '').toLowerCase();
            if (rowText.indexOf('bid') >= 0 && rowText.indexOf('offer') >= 0) {
                orderbookRow = row;
                break;
            }
        }

        if (!orderbookRow) {
            // Fallback: cari semua div yang isinya ada Bid dan Offer langsung di main-container
            if (window.Android && window.Android.onScrapingStatus) {
                window.Android.onScrapingStatus("Row container not found. Rows scanned: " + rowContainers.length);
            }
            return;
        }

        // Ambil semua direct children dari orderbookRow — setiap child = 1 widget saham
        function getVisibleTokens(el) {
            var tokens = [];
            var stack = [el];
            while(stack.length > 0) {
                var curr = stack.pop();
                if (curr.nodeType === 3) {
                    var t = (curr.nodeValue || '').trim();
                    if (t) tokens.push(t);
                } else if (curr.nodeType === 1) {
                    if (curr.tagName === 'INPUT') {
                        var v = (curr.value || '').trim();
                        if (v) tokens.push(v);
                    }
                    if (curr.childNodes && curr.childNodes.length > 0) {
                        for (var i = curr.childNodes.length - 1; i >= 0; i--) {
                            stack.push(curr.childNodes[i]);
                        }
                    }
                }
            }
            return tokens;
        }

        function getVisibleTokens(el) {
            var tokens = [];
            var stack = [el];
            while(stack.length > 0) {
                var curr = stack.pop();
                if (curr.nodeType === 3) {
                    var t = (curr.nodeValue || '').trim();
                    if (t) tokens.push(t);
                } else if (curr.nodeType === 1) {
                    if (curr.tagName === 'INPUT') {
                        var v = (curr.value || '').trim();
                        if (v) tokens.push(v);
                    }
                    if (curr.childNodes && curr.childNodes.length > 0) {
                        for (var i = curr.childNodes.length - 1; i >= 0; i--) {
                            stack.push(curr.childNodes[i]);
                        }
                    }
                }
            }
            return tokens;
        }

        var debugLog = [];
        var widgets = [];
        var allInputs = document.querySelectorAll('input');
        
        // 1. Temukan semua kontainer widget berdasarkan input Ticker
        for (var i = 0; i < allInputs.length; i++) {
            var input = allInputs[i];
            var v = (input.value || '').trim().toUpperCase();
            if (/^[A-Z]{3,5}$/.test(v) && !isSkipWord(v)) {
                var curr = input.parentElement;
                var widgetContainer = null;
                var maxDepth = 15;
                
                while (curr && curr.tagName !== 'BODY' && maxDepth > 0) {
                    var txt = (curr.textContent || '').toLowerCase();
                    if (txt.indexOf('bid') >= 0 && txt.indexOf('offer') >= 0 && txt.indexOf('lot') >= 0) {
                        // Cek berapa banyak input ticker di dalam kontainer ini
                        var inputsInside = curr.querySelectorAll('input');
                        var validTickersInside = 0;
                        for(var k=0; k<inputsInside.length; k++){
                            var iv = (inputsInside[k].value || '').trim().toUpperCase();
                            if (/^[A-Z]{3,5}$/.test(iv) && !isSkipWord(iv)) validTickersInside++;
                        }
                        
                        // Jika hanya ada 1 ticker, ini adalah widget spesifiknya!
                        if (validTickersInside === 1) {
                             widgetContainer = curr;
                        }
                        break;
                    }
                    curr = curr.parentElement;
                    maxDepth--;
                }
                
                if (widgetContainer && widgets.indexOf(widgetContainer) === -1) {
                    widgets.push(widgetContainer);
                }
            }
        }

        if (widgets.length === 0) {
            debugLog.push("0 widget containers found from " + allInputs.length + " inputs.");
        }

        for (var w = 0; w < widgets.length; w++) {
            try {
                var widget = widgets[w];
                var tokens = getVisibleTokens(widget);
                var textSnippet = tokens.join(' ').substring(0, 50);
                var wDebug = "W" + w + " (" + tokens.length + "t)";
                
                if (tokens.length < 5) {
                    debugLog.push(wDebug + ": <5 tokens. Text: " + textSnippet);
                    continue;
                }

                // ---- Cari Ticker ----
                var ticker = '';
                for (var ti = 0; ti < Math.min(tokens.length, 15); ti++) {
                    var t = tokens[ti].toUpperCase();
                    if (/^[A-Z]{3,5}$/.test(t) && !isSkipWord(t)) {
                        ticker = t;
                        break;
                    }
                }

                if (!ticker) {
                    debugLog.push(wDebug + ": No Ticker. Start: " + textSnippet);
                    continue;
                }
                if (processedTickers[ticker]) {
                    debugLog.push(wDebug + ": Duplicate " + ticker);
                    continue;
                }

                // ---- Cari Last Price & Change% ----
                var lastPrice = 0;
                var changePercent = 0.0;

                // ---- Cari header Bid/Offer ----
                var headerIdx = -1;
                for (var hi = 0; hi < Math.min(tokens.length, 40); hi++) {
                    var lc = tokens[hi].toLowerCase();
                    if (lc === 'bid' || lc === 'offer' || lc === 'b' || lc === 'o') {
                        headerIdx = hi;
                        break;
                    }
                }

                var scanFrom = headerIdx !== -1 ? headerIdx + 1 : 0;
                var nums = [];
                for (var di = scanFrom; di < tokens.length; di++) {
                    var nv = parseNum(tokens[di]);
                    if (nv > 0) nums.push(nv);
                }

                var bidLevels = [];
                var offerLevels = [];

                // Cari pasangan Bid-Offer: dua angka berdekatan (spread < 25%)
                for (var j = 0; j < nums.length - 1; j++) {
                    var v1 = nums[j];
                    var v2 = nums[j + 1];
                    var maxV = Math.max(v1, v2);
                    var spread = Math.abs(v1 - v2) / maxV;

                    if (v1 >= 1 && v2 >= 1 && v1 <= 99000 && v2 <= 99000 && spread <= 0.25) {
                        var bidPrice = Math.min(v1, v2);
                        var offerPrice = Math.max(v1, v2);
                        var bidLot = j > 0 ? nums[j - 1] : 0;
                        var offerLot = j + 2 < nums.length ? nums[j + 2] : 0;

                        if (bidLevels.length < 10) {
                            bidLevels.push({ price: bidPrice, lot: bidLot, frequency: 0 });
                        }
                        if (offerLevels.length < 10) {
                            offerLevels.push({ price: offerPrice, lot: offerLot, frequency: 0 });
                        }
                        
                        // Loncat untuk menghindari pencocokan lot sebagai harga
                        j += 2;
                    }
                }

                if (lastPrice === 0 && offerLevels.length > 0) lastPrice = offerLevels[0].price;
                if (lastPrice === 0 && bidLevels.length > 0) lastPrice = bidLevels[0].price;

                if (lastPrice > 0 && (bidLevels.length > 0 || offerLevels.length > 0)) {
                    processedTickers[ticker] = true;
                    var totalBid = 0, totalOffer = 0;
                    for (var bi = 0; bi < bidLevels.length; bi++) totalBid += bidLevels[bi].lot;
                    for (var oi = 0; oi < offerLevels.length; oi++) totalOffer += offerLevels[oi].lot;

                    results.push({
                        ticker: ticker,
                        lastPrice: lastPrice,
                        changePercent: changePercent,
                        timestamp: Date.now(),
                        bidLevels: bidLevels,
                        offerLevels: offerLevels,
                        totalBidLot: totalBid,
                        totalOfferLot: totalOffer
                    });
                } else {
                    debugLog.push(wDebug + ": " + ticker + " no price levels.");
                }
            } catch(innerErr) {
                debugLog.push("W" + w + " Err: " + innerErr.message);
            }
        }

        // ---- Kirim ke Android ----
        if (results.length > 0) {
            if (window.Android && window.Android.onOrderBookData) {
                window.Android.onOrderBookData(JSON.stringify(results));
            }
        } else {
            if (window.Android && window.Android.onScrapingStatus) {
                window.Android.onScrapingStatus(
                    "0/" + widgets.length + " widgets. Info: " + debugLog.join(" | ")
                );
            }
        }

    } catch(e) {
        if (window.Android && window.Android.onScrapingError) {
            window.Android.onScrapingError("JS: " + (e.message || e.toString()));
        }
    }
})();
