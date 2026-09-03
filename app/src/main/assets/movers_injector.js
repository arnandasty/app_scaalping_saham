(function() {
    try {
        // Cari semua link yang mengarah ke halaman simbol saham (contoh: /symbol/BBCA)
        const symbolLinks = document.querySelectorAll('a[href^="/symbol/"]');
        const tickers = new Set();
        
        symbolLinks.forEach(link => {
            const ticker = link.innerText.trim();
            // Validasi format ticker (4 huruf kapital)
            if (/^[A-Z]{4}$/.test(ticker)) {
                tickers.add(ticker);
            }
        });
        
        const topTickers = Array.from(tickers).slice(0, 8); // Ambil 8 teratas saja
        
        if (topTickers.length > 0) {
            // Kirim ke Android lewat MoversBridge
            if (window.MoversAndroid && typeof window.MoversAndroid.onTopTickers === 'function') {
                window.MoversAndroid.onTopTickers(JSON.stringify(topTickers));
            }
        }
    } catch (e) {
        console.error("Movers Injector Error:", e);
    }
})();
