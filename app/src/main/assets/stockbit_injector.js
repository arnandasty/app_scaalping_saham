window.autoFillTickers = function(tickers) {
    if (!tickers || tickers.length === 0) return;
    var inputs = document.querySelectorAll('input');
    var tickerInputs = [];
    
    // Kumpulkan input yang berada di dalam widget orderbook
    for (var i = 0; i < inputs.length; i++) {
        var curr = inputs[i].parentElement;
        var isWidget = false;
        // Cek ke atas apakah ini widget orderbook
        for (var d = 0; d < 10 && curr; d++) {
            var txt = (curr.textContent || '').toLowerCase();
            if (txt.indexOf('bid') >= 0 && txt.indexOf('offer') >= 0 && txt.indexOf('lot') >= 0) {
                isWidget = true; break;
            }
            curr = curr.parentElement;
        }
        if (isWidget) {
            tickerInputs.push(inputs[i]);
        }
    }
    
    // Fallback: Jika tidak menemukan widget yang sudah terisi, cari input yang BUKAN berada di header/nav
    if (tickerInputs.length === 0) {
        for (var j = 0; j < inputs.length; j++) {
            var isNav = false;
            var p = inputs[j].parentElement;
            while(p && p.tagName !== 'BODY') {
                var cName = (p.className || '').toLowerCase();
                if (p.tagName === 'HEADER' || p.tagName === 'NAV' || cName.indexOf('header') >= 0 || cName.indexOf('nav') >= 0 || p.id.indexOf('header') >= 0) {
                    isNav = true; break;
                }
                p = p.parentElement;
            }
            if (!isNav) {
                tickerInputs.push(inputs[j]);
            }
        }
    }
    
    if (tickerInputs.length === 0 || tickers.length === 0) {
        if (tickers.length > 0) {
            if (window._autoFillIndex >= tickers.length) window._autoFillIndex = 0;
            var fallbackTicker = tickers[window._autoFillIndex];
            window._autoFillIndex++;
            
            // Cek apakah sudah di halaman tersebut
            var urlMatch = window.location.pathname.match(/\/symbol\/([A-Z]{3,5})/i);
            if (urlMatch && urlMatch[1].toUpperCase() === fallbackTicker.toUpperCase()) return;

            // CARA 1: Gunakan Global Search Bar di Header agar React Router menangani navigasinya
            var globalSearch = document.querySelector('header input, nav input, [class*="header"] input');
            if (globalSearch) {
                var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                if (nativeInputValueSetter) {
                    nativeInputValueSetter.call(globalSearch, fallbackTicker);
                } else {
                    globalSearch.value = fallbackTicker;
                }
                globalSearch.dispatchEvent(new Event('input', { bubbles: true }));
                globalSearch.dispatchEvent(new Event('change', { bubbles: true }));
                globalSearch.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, keyCode: 13, key: 'Enter' }));
                return;
            }
            
            // CARA 2: Fallback ekstrim (risiko full reload)
            var a = document.createElement('a');
            a.href = '/symbol/' + fallbackTicker;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        }
        return;
    }
    
    // Gunakan input yang ditemukan
    var input = tickerInputs[0];
    
    // Rotasi: Ambil 1 ticker berikutnya secara berurutan setiap kali scraping dipanggil
    if (window._autoFillIndex >= tickers.length) {
        window._autoFillIndex = 0;
    }
    var targetTicker = tickers[window._autoFillIndex];
    window._autoFillIndex++;
    
    var currentVal = (input.value || '').trim().toUpperCase();
    if (currentVal === targetTicker.toUpperCase()) return; // Sudah sesuai
    
    // Hapus nilai lama dengan React bypass
    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
    if (nativeInputValueSetter) {
        nativeInputValueSetter.call(input, '');
    } else {
        input.value = '';
    }
    input.dispatchEvent(new Event('input', { bubbles: true }));
    
    // Ganti nilai input dengan ticker target
    if (nativeInputValueSetter) {
        nativeInputValueSetter.call(input, targetTicker);
    } else {
        input.value = targetTicker;
    }
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    
    // PENTING: JANGAN dispatch Enter ('keydown' 13) karena di Stockbit Desktop akan trigger redirect ke halaman /symbol/TICKER!
    // Membiarkan setTimeout(click) di bawah yang mengambil alih pemilihan dropdown.
    
    // Trigger klik otomatis menggunakan closure agar tidak tertukar antar widget
    (function(tickerToSearch, currentInput) {
        setTimeout(function() {
            // Cari dropdown items di dekat input tersebut atau secara global
            var dropItems = document.querySelectorAll('li, [class*="search-item"], [class*="symbol-item"]');
            for (var k = 0; k < dropItems.length; k++) {
                var txt = dropItems[k].textContent || '';
                if (txt.toUpperCase().indexOf(tickerToSearch) === 0) {
                    dropItems[k].click();
                    break;
                }
            }
        }, 300);
    })(targetTicker.toUpperCase(), input);
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
                        // Cek berapa banyak ticker unik di dalam kontainer ini
                        var inputsInside = curr.querySelectorAll('input');
                        var uniqueTickers = {};
                        var uniqueCount = 0;
                        for (var k = 0; k < inputsInside.length; k++) {
                            var iv = (inputsInside[k].value || '').trim().toUpperCase();
                            if (/^[A-Z]{3,5}$/.test(iv) && !isSkipWord(iv)) {
                                if (!uniqueTickers[iv]) {
                                    uniqueTickers[iv] = true;
                                    uniqueCount++;
                                }
                            }
                        }
                        
                        // Jika hanya ada 1 ticker unik (meskipun ada banyak input yang isinya sama), ini adalah widget spesifiknya!
                        if (uniqueCount === 1) {
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
            // FALLBACK UNTUK HALAMAN PROFIL (/symbol/TICKER)
            // Di halaman profil, orderbook tidak memiliki input ticker, jadi kita harus cari dari struktur tabel
            var urlMatch = window.location.pathname.match(/\/symbol\/([A-Z]{3,5})/i);
            var pageTicker = urlMatch ? urlMatch[1].toUpperCase() : null;
            
            if (pageTicker) {
                var tableHeaders = document.querySelectorAll('th, td, div');
                var profileWidget = null;
                for (var th = 0; th < tableHeaders.length; th++) {
                    var txt = (tableHeaders[th].textContent || '').trim().toLowerCase();
                    if (txt === 'bid' || txt === 'offer') {
                        var curr = tableHeaders[th].parentElement;
                        var depth = 0;
                        while (curr && curr.tagName !== 'BODY' && depth < 10) {
                            var pTxt = (curr.textContent || '').toLowerCase();
                            if (pTxt.indexOf('bid') >= 0 && pTxt.indexOf('offer') >= 0 && pTxt.indexOf('lot') >= 0) {
                                profileWidget = curr;
                            }
                            curr = curr.parentElement;
                            depth++;
                        }
                    }
                }
                
                if (profileWidget && widgets.indexOf(profileWidget) === -1) {
                    // Simpan ticker dari URL ke dalam atribut elemen agar terbaca oleh parser di bawah
                    profileWidget.setAttribute('data-injected-ticker', pageTicker);
                    widgets.push(profileWidget);
                }
            }
            
            if (widgets.length === 0) {
                debugLog.push("0 widget containers found from " + allInputs.length + " inputs and fallback.");
            }
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
                var ticker = widget.getAttribute('data-injected-ticker') || '';
                if (!ticker) {
                    for (var ti = 0; ti < Math.min(tokens.length, 15); ti++) {
                        var t = tokens[ti].toUpperCase();
                        if (/^[A-Z]{3,5}$/.test(t) && !isSkipWord(t)) {
                            ticker = t;
                            break;
                        }
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

                // ---- Cari ARA / ARB dari token widget ----
                // Stockbit menampilkan "ARA 1,135" dan "ARB 775" di header widget
                var araPrice = 0, arbPrice = 0;
                for (var ai = 0; ai < tokens.length - 1; ai++) {
                    var tok = tokens[ai].toUpperCase();
                    if (tok === 'ARA' || tok === 'AR+') {
                        var nextNum = parseNum(tokens[ai + 1]);
                        if (nextNum > 0) { araPrice = nextNum; }
                    }
                    if (tok === 'ARB' || tok === 'AR-') {
                        var nextNum2 = parseNum(tokens[ai + 1]);
                        if (nextNum2 > 0) { arbPrice = nextNum2; }
                    }
                }

                // ---- Cari changePercent ----
                var wText = widget.textContent || '';
                var pctMatch = wText.match(/\(([+-]?\d+[\.,]\d+)%\)/);
                if (pctMatch) {
                    changePercent = parseFloat(pctMatch[1].replace(',', '.'));
                }

                // Cari angka harga aktual (last price) dari token sebelum header orderbook
                var scanFrom = headerIdx !== -1 ? headerIdx + 1 : 0;
                
                for (var p = 0; p < (headerIdx !== -1 ? headerIdx : Math.min(tokens.length, 20)); p++) {
                    var tVal = tokens[p];
                    var val = parseNum(tVal);
                    // Ambil angka valid pertama yang murni harga (bukan persentase)
                    if (val >= 50 && val <= 99000 && !tVal.includes('%')) {
                        lastPrice = val;
                        break;
                    }
                }

                // Fallback terakhir
                if (lastPrice === 0) {
                    var lpMatch = wText.match(/(\d[\d,.]*)\s*[↑↓⬆⬇+\-]/);
                    if (lpMatch) {
                        var candidate = parseNum(lpMatch[1]);
                        if (candidate >= 50 && candidate <= 99000) lastPrice = candidate;
                    }
                }

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
                        totalOfferLot: totalOffer,
                        araPrice: araPrice,
                        arbPrice: arbPrice
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
