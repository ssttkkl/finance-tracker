package com.finance.tracker.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlin.js.js

@Composable
internal actual fun PlatformControlAccessibility(tag: String, state: ControlAccessibilityState) {
    DisposableEffect(tag, state) {
        val unregister = when (state) {
            is ControlAccessibilityState.Button -> registerControlAccessibility(tag, "button", !state.enabled, "")
            is ControlAccessibilityState.Radio -> registerControlAccessibility(tag, "radio", false, state.selected.toString())
        }
        onDispose(unregister)
    }
}

private fun registerControlAccessibility(tag: String, role: String, disabled: Boolean, checked: String): () -> Unit = js(
    """(() => {
        const attributes = ['role', 'aria-disabled', 'aria-checked'];
        const originals = new Map();
        let target = null;
        let observedRoot = null;
        let frame = null;
        const set = (node, name, value) => {
            if (value === null) {
                if (node.hasAttribute(name)) node.removeAttribute(name);
            } else if (node.getAttribute(name) !== value) node.setAttribute(name, value);
        };
        const find = (root) => {
            const node = root.getElementById(tag);
            if (node && node.isConnected) return node;
            for (const element of root.querySelectorAll('*')) {
                if (element.shadowRoot) {
                    const nested = find(element.shadowRoot);
                    if (nested) return nested;
                }
            }
            return null;
        };
        const sync = () => {
            if (frame !== null) cancelAnimationFrame(frame);
            frame = null;
            if (!target || !target.isConnected || target.id !== tag) target = find(document);
            if (!target) {
                rootObserver.disconnect();
                observedRoot = null;
                frame = requestAnimationFrame(sync);
                return;
            }
            const root = target.getRootNode();
            if (root !== observedRoot) {
                rootObserver.disconnect();
                rootObserver.observe(root, {subtree: true, childList: true, attributes: true, attributeFilter: ['id', 'role']});
                observedRoot = root;
            }
            if (!originals.has(target)) originals.set(target, attributes.map(name => target.getAttribute(name)));
            set(target, 'role', role);
            set(target, 'aria-disabled', String(disabled));
            set(target, 'aria-checked', checked === '' ? null : checked);
        };
        const rootObserver = new MutationObserver(sync);
        const hostObserver = new MutationObserver(() => {
            if (!target || !target.isConnected) sync();
        });
        hostObserver.observe(document, {subtree: true, childList: true});
        sync();
        return () => {
            hostObserver.disconnect();
            rootObserver.disconnect();
            if (frame !== null) cancelAnimationFrame(frame);
            for (const [node, values] of originals) attributes.forEach((name, index) => set(node, name, values[index]));
            originals.clear();
            target = null;
            observedRoot = null;
        };
    })()""",
)
