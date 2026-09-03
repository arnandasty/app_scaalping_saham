(function() {
    try {
        // Cari semua elemen yang mungkin berupa link saham
        const elements = document.querySelectorAll('a[href*="/symbol/"], a[href*="/stock/"], div[role="link"]');
        const tickers = new Set();
        let log = "Found " + elements.length + " elements. ";
        
        elements.forEach(el => {
            const text = el.innerText.trim();
            // Coba ambil kata pertama jika ada spasi, misal "BBCA - Bank Central Asia"
            const firstWord = text.split(/\s+/)[0];
            if (/^[A-Z]{4}$/.test(firstWord)) {
                tickers.add(firstWord);
            }
        });
        
        const topTickers = Array.from(tickers).slice(0, 8); // Ambil 8 teratas saja
        
        if (window.MoversAndroid) {
            if (topTickers.length > 0) {
                window.MoversAndroid.onTopTickers(JSON.stringify(topTickers));
                window.MoversAndroid.onDebug("Sukses ambil " + topTickers.length + " movers.");
            } else {
                window.MoversAndroid.onDebug("Gagal! " + log + " Teks contoh: " + (elements.length > 0 ? elements[0].innerText.substring(0,20) : "Kosong"));
            }
        }
    } catch (e) {
        console.error("Movers Injector Error:", e);
    }
})();
