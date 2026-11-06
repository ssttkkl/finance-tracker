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

    if (!supportsWasmRuntime()) {
        document.getElementById("startup-screen").setAttribute("hidden", "");
        document.getElementById("browser-unsupported").removeAttribute("hidden");
        return;
    }

    var sources = ["/font-mirror.js", "/compose-focus.js", "/webApp.js"];
    for (var index = 0; index < sources.length; index += 1) {
        var script = document.createElement("script");
        script.src = sources[index];
        script.async = false;
        document.body.appendChild(script);
    }
}());
