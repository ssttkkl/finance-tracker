(function () {
    // The JS development target does not require WasmGC.
    var sources = ["/font-mirror.js", "/compose-focus.js", "/webApp.js"];
    for (var index = 0; index < sources.length; index += 1) {
        var script = document.createElement("script");
        script.src = sources[index];
        script.async = false;
        document.body.appendChild(script);
    }
}());
