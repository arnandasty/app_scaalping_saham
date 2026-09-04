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
            if (cols.length >= 7) {
                var timeText = cols[0].textContent || '';
                var tickerText = cols[1].textContent || '';
                var actionEl = cols[3].querySelector('p');
                var lotEl = cols[4].querySelector('p');
                
                var actionType = actionEl ? actionEl.getAttribute('action') : null;
                var isBuy = (actionType === '1' || (actionEl && actionEl.textContent === 'Buy'));
                var isSell = (actionType === '2' || (actionEl && actionEl.textContent === 'Sell'));
                
                var lotStr = lotEl ? (lotEl.textContent || '') : '0';
                var lot = parseInt(lotStr.replace(/,/g, ''), 10) || 0;
                
                if (tickerText && (isBuy || isSell) && lot > 0) {
                    newTransactions.push({
                        id: rowKey,
                        ticker: tickerText.trim(),
                        time: timeText.trim(),
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
