(function() {
    if (window._probeInstalled) return;
    window._probeInstalled = true;

    function sendToAndroid(type, url, data) {
        try {
            if (window.AndroidProbe && window.AndroidProbe.onProbeCaptured) {
                var sample = typeof data === 'string' ? data : JSON.stringify(data);
                // Jangan pangkas jika merupakan data marketdetectors atau fetch/xhr data penting
                if (type !== 'FETCH_DATA' && type !== 'XHR_DATA' && (!url || url.indexOf('marketdetectors') < 0)) {
                    if (sample && sample.length > 300) {
                        sample = sample.substring(0, 300) + '...';
                    }
                }
                window.AndroidProbe.onProbeCaptured(type, url || '', sample || '');
            }
        } catch(e) {}
    }

    function jwtExpMs(jwt) {
        try {
            if (!jwt || typeof jwt !== 'string') return 0;
            var parts = jwt.split('.');
            if (parts.length < 2) return 0;
            var b = parts[1].replace(/-/g, '+').replace(/_/g, '/');
            while (b.length % 4) b += '=';
            var json = JSON.parse(atob(b));
            return (json.exp || 0) * 1000;
        } catch (e) { return 0; }
    }

    function captureToken(raw) {
        if (!raw || typeof raw !== 'string') return;
        var t = raw.trim();
        if (t.toLowerCase().indexOf('bearer ') === 0) {
            t = t.substring(7).trim();
        }
        if (t.length > 20) {
            // Cegah token basi (mis. header fetch pakai token lama) menimpa token bagus:
            // hanya teruskan bila exp-nya lebih lama dari yang terakhir dikirim.
            try {
                var e = jwtExpMs(t);
                var lastE = jwtExpMs(window._lastSentToken || '');
                if (window._lastSentToken && e <= lastE) return;
                window._lastSentToken = t;
            } catch (ign) {}
            if (window.AndroidProbe && window.AndroidProbe.onTokenCaptured) {
                window.AndroidProbe.onTokenCaptured(t);
            }
        }
    }

    // 0. Auto-scan localStorage & sessionStorage — kumpulkan SEMUA kandidat JWT,
    // lalu kirim hanya yang exp-nya paling lama (ter-fresh). Jangan kirim yang pertama
    // ketemu karena bisa jadi token basi menimpa token bagus (kasus len 831 nimpa 852).
    function scanStoragesForToken() {
        try {
            var storages = [localStorage, sessionStorage];
            var allKeys = [];
            var best = null;
            var bestExp = 0;
            var consider = function(cand) {
                if (!cand || typeof cand !== 'string') return;
                var t = cand.trim().replace(/^["']|["']$/g, '');
                if (t.toLowerCase().indexOf('bearer ') === 0) t = t.substring(7).trim();
                if (!/^eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}/.test(t)) return;
                var e = jwtExpMs(t);
                if (e > bestExp) { bestExp = e; best = t; }
            };
            for (var s = 0; s < storages.length; s++) {
                var st = storages[s];
                if (!st) continue;
                for (var i = 0; i < st.length; i++) {
                    var k = st.key(i);
                    if (!k) continue;
                    if (s === 0) allKeys.push(k);
                    var val = st.getItem(k);
                    if (!val || typeof val !== 'string') continue;

                    // 1. Semua pola JWT di value (bisa lebih dari 1)
                    var re = /eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}/g;
                    var m;
                    while ((m = re.exec(val)) !== null) { consider(m[0]); }

                    // 2. Field JSON token/accessToken
                    var lk = k.toLowerCase();
                    if (lk.indexOf('token') >= 0 || lk.indexOf('auth') >= 0 || lk.indexOf('user') >= 0 || lk.indexOf('session') >= 0) {
                        if (val.charAt(0) === '{' || val.charAt(0) === '[') {
                            try {
                                var parsed = JSON.parse(val);
                                var cand = parsed.token || parsed.accessToken || parsed.access_token || parsed.jwt || parsed.idToken;
                                if (!cand && parsed.auth) {
                                    var a = typeof parsed.auth === 'string' ? JSON.parse(parsed.auth) : parsed.auth;
                                    cand = a.token || a.accessToken || a.access_token;
                                }
                                if (cand) consider(cand);
                            } catch (err) {}
                        } else if (val.length > 20 && !val.startsWith('http')) {
                            consider(val);
                        }
                    }
                }
            }
            // Hanya kirim yang ter-fresh, dan hanya bila beda dari yang terakhir dikirim
            // agar token basi tidak flip-flop menimpa token bagus.
            if (best && best !== window._lastSentToken) {
                window._lastSentToken = best;
                captureToken(best);
            }
            if (allKeys.length > 0 && !window._loggedLSKeys) {
                window._loggedLSKeys = true;
                sendToAndroid('LS_KEYS', window.location.host, allKeys.join(', '));
            }
        } catch (e) {}
    }

    scanStoragesForToken();
    setInterval(scanStoragesForToken, 3000);

    // 1. Intercept WebSocket (WSS) — ABAIKAN socket orderbook/chat agar log tidak banjir.
    // Hanya teruskan WS yang berpotensi berisi candle/token (history/chart/stream penting tidak pakai WS).
    function isNoisyWs(url) {
        if (!url) return true;
        var u = url.toLowerCase();
        return u.indexOf('wss-jkt') >= 0 || u.indexOf('wssocial') >= 0 ||
               u.indexOf('crisp.chat') >= 0 || u.indexOf('primus') >= 0 ||
               u.indexOf('sockjs') >= 0 || u.indexOf('socket.io') >= 0;
    }
    if (typeof window.WebSocket !== 'undefined') {
        var OriginalWS = window.WebSocket;
        window.WebSocket = function(url, protocols) {
            var ws;
            try {
                if (protocols) {
                    ws = new OriginalWS(url, protocols);
                } else {
                    ws = new OriginalWS(url);
                }
            } catch(err) {
                if (!isNoisyWs(url)) sendToAndroid('WS_ERROR', url, err.message);
                throw err;
            }

            if (isNoisyWs(url)) return ws; // diam total untuk orderbook/chat socket
            sendToAndroid('WS_CONNECT', url, 'Connecting...');

            ws.addEventListener('open', function() {
                sendToAndroid('WS_OPEN', url, 'Connected');
            });

            ws.addEventListener('message', function(event) {
                sendToAndroid('WS_MSG', url, event.data);
            });

            ws.addEventListener('error', function() {
                sendToAndroid('WS_ERR', url, 'Error event');
            });

            ws.addEventListener('close', function(e) {
                sendToAndroid('WS_CLOSE', url, 'Code: ' + e.code);
            });

            var origSend = ws.send;
            ws.send = function(data) {
                sendToAndroid('WS_SEND', url, data);
                return origSend.apply(this, arguments);
            };

            return ws;
        };

        window.WebSocket.prototype = OriginalWS.prototype;
        window.WebSocket.CONNECTING = OriginalWS.CONNECTING;
        window.WebSocket.OPEN = OriginalWS.OPEN;
        window.WebSocket.CLOSING = OriginalWS.CLOSING;
        window.WebSocket.CLOSED = OriginalWS.CLOSED;
    }

    // 2. Intercept EventSource (Server-Sent Events / SSE)
    if (typeof window.EventSource !== 'undefined') {
        var OriginalSSE = window.EventSource;
        window.EventSource = function(url, options) {
            var sse = new OriginalSSE(url, options);
            sendToAndroid('SSE_CONNECT', url, 'Connecting...');

            sse.addEventListener('message', function(event) {
                sendToAndroid('SSE_MSG', url, event.data);
            });

            sse.addEventListener('error', function() {
                sendToAndroid('SSE_ERR', url, 'Error event');
            });

            return sse;
        };
        window.EventSource.prototype = OriginalSSE.prototype;
    }

    // 3. Intercept Fetch untuk endpoint market/orderbook/stream/quote & tangkap Authorization header
    if (typeof window.fetch !== 'undefined') {
        var origFetch = window.fetch;
        window.fetch = function() {
            var input = arguments[0];
            var init = arguments[1];
            var urlStr = (typeof input === 'string') ? input : (input && input.url ? input.url : '');

            // Tangkap header Authorization jika ada
            try {
                var authH = null;
                if (init && init.headers) {
                    if (typeof init.headers.get === 'function') {
                        authH = init.headers.get('Authorization') || init.headers.get('authorization');
                    } else if (typeof init.headers === 'object') {
                        authH = init.headers['Authorization'] || init.headers['authorization'];
                    }
                } else if (input && input.headers && typeof input.headers.get === 'function') {
                    authH = input.headers.get('Authorization') || input.headers.get('authorization');
                }
                if (authH) captureToken(authH);
            } catch(e) {}

            var isInteresting = /orderbook|running|trade|stream|market|quote|depth|ticker|marketdetectors|history|chart|candle|bars|udf|tradingview/i.test(urlStr);

            return origFetch.apply(this, arguments).then(function(response) {
                if (isInteresting) {
                    try {
                        var clone = response.clone();
                        clone.text().then(function(txt) {
                            sendToAndroid('FETCH_DATA', urlStr, txt);
                        }).catch(function(){});
                    } catch(e) {}
                }
                return response;
            });
        };
    }

    // 4. Intercept XMLHttpRequest (XHR)
    if (typeof window.XMLHttpRequest !== 'undefined') {
        var origOpen = window.XMLHttpRequest.prototype.open;
        var origSend = window.XMLHttpRequest.prototype.send;
        var origSetHeader = window.XMLHttpRequest.prototype.setRequestHeader;

        window.XMLHttpRequest.prototype.open = function(method, url) {
            this._probeUrl = url;
            return origOpen.apply(this, arguments);
        };

        window.XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
            try {
                if (header && header.toLowerCase() === 'authorization') {
                    captureToken(value);
                }
            } catch(e) {}
            return origSetHeader.apply(this, arguments);
        };

        window.XMLHttpRequest.prototype.send = function() {
            var url = this._probeUrl || '';
            var isInteresting = /orderbook|running|trade|stream|market|quote|depth|ticker|marketdetectors|history|chart|candle|bars|udf|tradingview/i.test(url);
            if (isInteresting) {
                this.addEventListener('load', function() {
                    sendToAndroid('XHR_DATA', url, this.responseText);
                });
            }
            return origSend.apply(this, arguments);
        };
    }

    sendToAndroid('PROBE_READY', window.location.href, 'Probe successfully active in WebView');
})();
