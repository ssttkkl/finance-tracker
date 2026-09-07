import { createApiClient, type FetchLike, type FetchRequestInit } from "@finance-tracker/api-client";
import { nativeApiOrigin } from "./config";
import { nativeTokenStore } from "./tokenStore";

const nativeFetch: FetchLike = (url: string, init?: FetchRequestInit) => fetch(url, {
  method: init?.method,
  headers: init?.headers,
  body: init?.body as BodyInit | null | undefined,
  signal: init?.signal as AbortSignal | undefined,
});

export const mobileApiClient = createApiClient({
  baseUrl: nativeApiOrigin,
  fetch: nativeFetch,
  tokenStore: nativeTokenStore,
  allowInsecureHttp: true,
});
