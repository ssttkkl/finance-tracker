(() => {
  let cancelPending = () => {};
  document.addEventListener("keydown", (event) => {
    cancelPending();
    if (event.key !== "Tab" || event.ctrlKey || event.altKey || event.metaKey || event.isComposing) return;
    const input = event.composedPath().find((node) => node instanceof HTMLElement
      && node.classList.contains("compose-backing-field"));
    const root = input?.getRootNode();
    if (!(root instanceof ShadowRoot)) return;
    const canvases = root.querySelectorAll("canvas");
    if (canvases.length !== 1) return;
    const canvas = canvases[0];

    const observer = new MutationObserver(() => {
      if (input.isConnected) return;
      cancelPending();
      const active = document.activeElement;
      if (document.hasFocus() && canvas.isConnected && !root.activeElement
        && (!active || active === document.body || active === document.documentElement)) {
        canvas.focus({ preventScroll: true });
      }
    });
    // Input disposal follows Compose's focus update, rather than the next RAF.
    observer.observe(root, { childList: true, subtree: true });
    const timeout = setTimeout(() => cancelPending(), 1500);
    cancelPending = () => {
      observer.disconnect();
      clearTimeout(timeout);
      cancelPending = () => {};
    };
  }, true);
  document.addEventListener("pointerdown", () => cancelPending(), true);
  window.addEventListener("blur", () => cancelPending());
  window.addEventListener("pagehide", () => cancelPending());
})();
