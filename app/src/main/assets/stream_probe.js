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

    function captureToken(raw) {
        if (!raw || typeof raw !== 'string') return;
        var t = raw.trim();
        if (t.toLowerCase().indexOf('bearer ') === 0) {
            t = t.substring(7).trim();
        }
        if (t.length > 20) {
            if (window.AndroidProbe && window.AndroidProbe.onTokenCaptured) {
                window.AndroidProbe.onTokenCaptured(t);
            }
        }
    }

    // 0. Auto-scan localStorage & sessionStorage untuk capture token otentikasi Stockbit
    function scanStoragesForToken() {
        try {
            var storages = [localStorage, sessionStorage];
            var allKeys = [];
            for (var s = 0; s < storages.length; s++) {
                var st = storages[s];
                if (!st) continue;
                for (var i = 0; i < st.length; i++) {
                    var k = st.key(i);
                    if (!k) continue;
                    if (s === 0) allKeys.push(k);
                    var val = st.getItem(k);
                    if (!val || typeof val !== 'string') continue;

                    // 1. Cek pola standar JWT (eyJ...)
                    var jwtMatch = val.match(/eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}/);
                    if (jwtMatch && jwtMatch[0]) {
                        captureToken(jwtMatch[0]);
                        return;
                    }

                    // 2. Cek JSON jika ada field token/accessToken
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
                                if (cand && typeof cand === 'string' && cand.length > 20) {
                                    captureToken(cand);
                                    return;
                                }
                            } catch(err) {}
                        } else if (val.length > 20 && !val.startsWith('http')) {
                            captureToken(val.replace(/^["']|["']$/g, ''));
                            return;
                        }
                    }
                }
            }
            if (allKeys.length > 0 && !window._loggedLSKeys) {
                window._loggedLSKeys = true;
                sendToAndroid('LS_KEYS', window.location.host, allKeys.join(', '));
            }
        } catch(e) {}
    }

    scanStoragesForToken();
    setInterval(scanStoragesForToken, 3000);

    // 1. Intercept WebSocket (WSS)
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
                sendToAndroid('WS_ERROR', url, err.message);
                throw err;
            }

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

            var isInteresting = /orderbook|running|trade|stream|market|quote|depth|ticker|marketdetectors/i.test(urlStr);

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
            var isInteresting = /orderbook|running|trade|stream|market|quote|depth|ticker|marketdetectors/i.test(url);
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
