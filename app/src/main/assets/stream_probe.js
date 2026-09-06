(function() {
    if (window._probeInstalled) return;
    window._probeInstalled = true;

    function sendToAndroid(type, url, data) {
        try {
            if (window.AndroidProbe && window.AndroidProbe.onProbeCaptured) {
                var sample = typeof data === 'string' ? data : JSON.stringify(data);
                if (sample && sample.length > 300) {
                    sample = sample.substring(0, 300) + '...';
                }
                window.AndroidProbe.onProbeCaptured(type, url || '', sample || '');
            }
        } catch(e) {}
    }

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

    // 3. Intercept Fetch untuk endpoint market/orderbook/stream/quote
    if (typeof window.fetch !== 'undefined') {
        var origFetch = window.fetch;
        window.fetch = function() {
            var url = arguments[0];
            var urlStr = (typeof url === 'string') ? url : (url && url.url ? url.url : '');
            var isInteresting = /orderbook|running|trade|stream|market|quote|depth|ticker/i.test(urlStr);

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

        window.XMLHttpRequest.prototype.open = function(method, url) {
            this._probeUrl = url;
            return origOpen.apply(this, arguments);
        };

        window.XMLHttpRequest.prototype.send = function() {
            var url = this._probeUrl || '';
            var isInteresting = /orderbook|running|trade|stream|market|quote|depth|ticker/i.test(url);
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
