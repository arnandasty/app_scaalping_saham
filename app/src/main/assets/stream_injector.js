(function() {
    // 1. Pastikan panel Running Trade terbuka
    var runningTradeButton = document.querySelector('button[data-cy="right-menu-running_trade"]');
    if (runningTradeButton) {
        var isOpen = runningTradeButton.parentElement && runningTradeButton.parentElement.className.indexOf('active') >= 0;
        if (!isOpen && !window._runningTradeClicked) {
            runningTradeButton.click();
            window._runningTradeClicked = true;
        }
    }

    // 2. Parse tabel transaksi
    var rows = document.querySelectorAll('tr[data-row-key]');
    if (!rows || rows.length === 0) return;

    var newTransactions = [];
    
    if (!window._seenTradeIds) {
        window._seenTradeIds = {};
        window._seenTradeIdsList = [];
    }

    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var rowKey = row.getAttribute('data-row-key'); // "WEGE|1206195|1788496199"
        
        if (!rowKey || window._seenTradeIds[rowKey]) continue; // Sudah diproses sebelumnya

        try {
            var cols = row.querySelectorAll('td');
            if (cols.length >= 5) {
                var timeText = (cols[0].textContent || '').trim();
                var tickerText = (cols[1].textContent || '').trim().toUpperCase();
                var tickerFromKey = (rowKey.split('|')[0] || '').trim().toUpperCase();
                var ticker = (tickerText && /^[A-Z]{2,5}$/.test(tickerText)) ? tickerText : tickerFromKey;

                var priceText = cols.length > 2 ? (cols[2].textContent || '') : '';
                var price = parseInt(priceText.replace(/[,.]/g, '').trim(), 10) || 0;

                // Fallback scan harga jika cols[2] kosong
                if (price <= 0) {
                    for (var c = 1; c < cols.length; c++) {
                        var cVal = (cols[c].textContent || '').replace(/[,.]/g, '').trim();
                        var num = parseInt(cVal, 10);
                        if (!isNaN(num) && num >= 1 && num <= 99000 && !cols[c].textContent.includes(':')) {
                            price = num;
                            break;
                        }
                    }
                }

                var actionEl = cols.length > 3 ? cols[3].querySelector('p') : null;
                var actionText = actionEl ? (actionEl.textContent || '') : (cols.length > 3 ? cols[3].textContent || '' : '');
                var actionType = actionEl ? actionEl.getAttribute('action') : null;
                var isBuy = (actionType === '1' || actionText.indexOf('Buy') >= 0 || actionText.indexOf('B') === 0);
                var isSell = (actionType === '2' || actionText.indexOf('Sell') >= 0 || actionText.indexOf('S') === 0);

                var lotEl = cols.length > 4 ? cols[4].querySelector('p') : null;
                var lotStr = lotEl ? (lotEl.textContent || '') : (cols.length > 4 ? cols[4].textContent || '' : '0');
                var lot = parseInt(lotStr.replace(/[,.]/g, '').trim(), 10) || 0;

                if (ticker && (isBuy || isSell) && lot > 0) {
                    newTransactions.push({
                        id: rowKey,
                        ticker: ticker,
                        time: timeText,
                        price: price,
                        type: isBuy ? 'BUY' : 'SELL', // HAKA = BUY, HAKI = SELL
                        lot: lot
                    });
                }
            }
        } catch (e) {
            // Abaikan error parsial per baris
        }
    }

    if (newTransactions.length > 0) {
        for (var n = 0; n < newTransactions.length; n++) {
            var tid = newTransactions[n].id;
            window._seenTradeIds[tid] = true;
            window._seenTradeIdsList.push(tid);
        }
        // Batasi ukuran memory
        while (window._seenTradeIdsList.length > 2000) {
            var oldId = window._seenTradeIdsList.shift();
            delete window._seenTradeIds[oldId];
        }
        
        if (window.AndroidStream && window.AndroidStream.onStreamData) {
            window.AndroidStream.onStreamData(JSON.stringify(newTransactions));
        }
    }
})();
