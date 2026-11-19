(function () {
    // Keep this gate parseable before loading the modern Kotlin/Wasm bundle.
    function supportsWasmRuntime() {
        if (typeof WebAssembly !== "object" || WebAssembly === null
            || typeof WebAssembly.validate !== "function"
            || typeof WebAssembly.instantiateStreaming !== "function"
            || typeof WebAssembly.Tag !== "function") {
            return false;
        }

        // (module (type (struct))) probes the finalized GC encoding.
        var gc = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 3, 1, 95, 0]);
        // A legacy try/throw/catch, rather than only a tag shared with the new EH proposal.
        // (module (tag $e) (func (try (do (throw $e)) (catch $e))))
        var legacyExceptions = new Uint8Array([
            0, 97, 115, 109, 1, 0, 0, 0,
            1, 4, 1, 96, 0, 0, 3, 2, 1, 0, 13, 3, 1, 0, 0,
            10, 11, 1, 9, 0, 6, 64, 8, 0, 7, 0, 11, 11
        ]);
        return WebAssembly.validate(gc) && WebAssembly.validate(legacyExceptions);
    }

    var release = "__FT_WEB_RELEASE__";
    if (!/^[0-9a-f]{40}$/.test(release)) release = "unknown";
    var diagnosticKey = "finance-tracker:runtime-diagnostics";
    var entries = [];
    var sources = ["/font-mirror.js", "/compose-focus.js", "/webApp.js"];
    var errorSources = sources.concat(["/startup.js", "/FinanceTracker-webApp.import-object.mjs"]);
    var requestIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

    function createErrorReporter() {
        var dsn = "__FT_WEB_SENTRY_DSN__";
        if (dsn.indexOf("://") === -1) return function () {};
        var pending = [];
        var sdk;
        var script = document.createElement("script");
        script.src = "/sentry-browser.min.js";
        script.addEventListener("load", function () {
            try {
                sdk = window.Sentry;
                sdk.init({
                    dsn: dsn,
                    release: release === "unknown" ? undefined : release,
                    environment: "production",
                    sendDefaultPii: false,
                    dataCollection: { userInfo: false },
                    defaultIntegrations: false,
                    autoSessionTracking: false,
                    sendClientReports: false,
                    tracePropagationTargets: [],
                    transportOptions: { fetchOptions: { referrerPolicy: "no-referrer", credentials: "omit" } },
                    beforeSend: function (event) {
                        var kinds = ["startup", "runtime_error", "script_load_failed", "unhandled_rejection", "auth_http_failure", "auth_failure"];
                        var kind = kinds.find(function (value) { return event.message === "finance_tracker.web." + value; });
                        if (!kind) return null;
                        var candidate = event.tags || {};
                        var tags = {};
                        if (errorSources.indexOf(candidate.script) !== -1) tags.script = candidate.script;
                        if (["login", "register", "session"].indexOf(candidate.operation) !== -1) tags.operation = candidate.operation;
                        if (["wasm_unsupported", "isolation_unavailable"].indexOf(candidate.reason) !== -1) tags.reason = candidate.reason;
                        if (typeof candidate.request_id === "string" && requestIdPattern.test(candidate.request_id)) tags.request_id = candidate.request_id;
                        if (typeof candidate.http_status === "string" && /^5[0-9]{2}$/.test(candidate.http_status)) tags.http_status = candidate.http_status;
                        if (typeof candidate.line === "string" && /^[0-9]{1,8}$/.test(candidate.line)) tags.line = candidate.line;
                        if (typeof candidate.column === "string" && /^[0-9]{1,8}$/.test(candidate.column)) tags.column = candidate.column;
                        return {
                            event_id: event.event_id, timestamp: event.timestamp, platform: "javascript",
                            release: release === "unknown" ? undefined : release, environment: "production",
                            level: "error", message: event.message, tags: tags,
                            fingerprint: [event.message, tags.script || tags.operation || tags.reason || kind]
                        };
                    }
                });
                pending.forEach(send);
            } catch (_) {
                sdk = undefined;
            } finally {
                pending = [];
            }
        });
        script.addEventListener("error", function () { pending = []; });
        document.body.appendChild(script);

        function send(event) {
            try { sdk.captureEvent(event); } catch (_) {}
        }
        return function (kind, tags) {
            var event = {
                message: "finance_tracker.web." + kind,
                tags: tags
            };
            if (sdk) send(event);
            else if (pending.length < 8) pending.push(event);
        };
    }

    var reportError = createErrorReporter();
    function record(kind, fields, failed) {
        var entry = { time: Date.now(), release: release, kind: kind };
        Object.keys(fields).forEach(function (key) { entry[key] = fields[key]; });
        entries.push(entry);
        if (entries.length > 32) entries.splice(1, 1);
        try {
            window.localStorage.setItem(diagnosticKey, JSON.stringify(entries));
        } catch (_) {
            // Browser privacy settings or quota can disable storage.
        }
        try {
            var log = failed ? console.error : console.info;
            log.call(console, "[Finance Tracker runtime]", entry);
        } catch (_) {
            // Keep startup independent of diagnostic output.
        }
    }

    window.addEventListener("error", function (event) {
        var fields = {};
        var origin = window.location && window.location.origin;
        for (var i = 0; origin && i < errorSources.length; i += 1) {
            if (event.filename !== origin + errorSources[i]) continue;
            fields.resource = errorSources[i];
            if (typeof event.lineno === "number" && isFinite(event.lineno)) fields.line = event.lineno;
            if (typeof event.colno === "number" && isFinite(event.colno)) fields.column = event.colno;
            break;
        }
        record("error", fields, true);
        var tags = {};
        if (fields.resource) tags.script = fields.resource;
        if (fields.line !== undefined) tags.line = String(fields.line);
        if (fields.column !== undefined) tags.column = String(fields.column);
        reportError("runtime_error", tags);
    });
    window.addEventListener("unhandledrejection", function () {
        record("unhandled_rejection", {}, true);
        reportError("unhandled_rejection", {});
    });
    window.financeTrackerRecordAuthResponse = function (operation, status, requestId) {
        if (["login", "register", "session"].indexOf(operation) === -1
            || typeof status !== "number" || status < 100 || status > 599 || status % 1 !== 0) return;
        var fields = { operation: operation, status: status };
        if (typeof requestId === "string" && requestIdPattern.test(requestId)) fields.requestId = requestId;
        record("auth_response", fields, false);
        if (status >= 500) {
            var tags = { operation: operation, http_status: String(status) };
            if (fields.requestId) tags.request_id = fields.requestId;
            reportError("auth_http_failure", tags);
        }
    };

    window.financeTrackerReportAuthDiagnostic = function (line) {
        var fields = line.split("\t");
        if (fields[1] === "AUTHENTICATION" && fields[2] === "AUTHENTICATE"
            && fields[3] === "unknown_error" && fields[4] === "") {
            reportError("auth_failure", {});
        }
    };

    function supportsOpfs() {
        try {
            return !!navigator.storage && typeof navigator.storage.getDirectory === "function";
        } catch (_) {
            return false;
        }
    }

    var wasmSupported = supportsWasmRuntime();
    record("startup", {
        wasmSupported: wasmSupported,
        secureContext: window.isSecureContext === true,
        crossOriginIsolated: window.crossOriginIsolated === true,
        sharedArrayBuffer: typeof SharedArrayBuffer === "function",
        webLocks: !!navigator.locks,
        opfs: supportsOpfs()
    }, !wasmSupported);

    if (!wasmSupported || !window.crossOriginIsolated || typeof SharedArrayBuffer !== "function") {
        var reason = !wasmSupported ? "wasm_unsupported" : "isolation_unavailable";
        reportError("startup", { reason: reason });
    }

    if (!wasmSupported) {
        document.getElementById("startup-screen").setAttribute("hidden", "");
        document.getElementById("browser-unsupported").removeAttribute("hidden");
        return;
    }

    var importWorkerClient = document.createElement("script");
    importWorkerClient.type = "module";
    importWorkerClient.src = "/cash-import-worker/client.mjs";
    document.body.appendChild(importWorkerClient);

    sources.forEach(function (source) {
        var script = document.createElement("script");
        script.src = source;
        script.async = false;
        script.addEventListener("error", function () {
            record("script_load_failed", { resource: source }, true);
            reportError("script_load_failed", { script: source });
        });
        document.body.appendChild(script);
    });
}());
