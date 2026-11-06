// Real same-origin iframe: it has an independent JS realm and must wait for the metadata Web Lock.
export async function probeMetadataLock(key) {
  let release, acquired, listener, timeout;
  const entered = new Promise(resolve => { acquired = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  const lock = navigator.locks.request('finance-tracker-ledger-session-locator-v1', async () => {
    acquired(); await gate;
  });
  const frame = document.createElement('iframe');
  frame.hidden = true;
  const marker = crypto.randomUUID();
  try {
    localStorage.setItem(key, 'original');
    await entered;
    const result = new Promise((resolve, reject) => {
      timeout = setTimeout(() => reject(new Error('locator_probe_timeout')), 10000);
      listener = event => {
        if (event.source !== frame.contentWindow || event.origin !== location.origin || event.data?.marker !== marker) return;
        if (event.data.phase === 'ready') {
          setTimeout(() => {
            if (localStorage.getItem(key) !== 'original') reject(new Error('locator_probe_lock_bypassed'));
            release();
          }, 30);
        } else if (event.data.phase === 'result') {
          if (event.data.accepted === true) resolve(); else reject(new Error('locator_probe_cas_rejected'));
        } else if (event.data.phase === 'error') reject(new Error('locator_probe_frame_failed'));
      };
      addEventListener('message', listener);
    });
    frame.srcdoc = `<script type="module">
      import {compareAndSetLocator} from '${location.origin}/ledger-storage/session-locator.mjs';
      const marker = ${JSON.stringify(marker)};
      const targetOrigin = ${JSON.stringify(location.origin)};
      parent.postMessage({marker, phase:'ready'}, targetOrigin);
      try {
        const accepted = await compareAndSetLocator(${JSON.stringify(key)}, 'original', 'updated');
        parent.postMessage({marker, phase:'result', accepted}, targetOrigin);
      } catch (_) { parent.postMessage({marker, phase:'error'}, targetOrigin); }
    </script>`;
    document.body.append(frame);
    await result;
    if (localStorage.getItem(key) !== 'updated') throw new Error('locator_probe_update_missing');
    return 'blocked-then-updated';
  } finally {
    release(); await lock;
    clearTimeout(timeout);
    if (listener) removeEventListener('message', listener);
    frame.remove(); localStorage.removeItem(key);
  }
}
