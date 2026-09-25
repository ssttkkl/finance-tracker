(() => {
  const originalFetch = window.fetch.bind(window);
  const upstreamPrefix = "/s/notosanssc/v40/";

  window.fetch = (input, init) => {
    const sourceUrl = input instanceof Request ? input.url : String(input);
    let requestUrl;
    try {
      requestUrl = new URL(sourceUrl, window.location.href);
    } catch {
      return originalFetch(input, init);
    }
    if (requestUrl.origin !== "https://fonts.gstatic.com"
      || !requestUrl.pathname.startsWith(upstreamPrefix)) {
      return originalFetch(input, init);
    }

    const relativePath = requestUrl.pathname.slice("/s/notosanssc".length);
    if (!/^\/v40\/[\w.-]+\.woff2$/.test(relativePath)) {
      return originalFetch(input, init);
    }

    const localUrl = new URL(`/fonts/notosanssc${relativePath}`, window.location.origin);
    const localRequest = input instanceof Request ? new Request(localUrl, input) : localUrl.href;
    return originalFetch(localRequest, init);
  };
})();
