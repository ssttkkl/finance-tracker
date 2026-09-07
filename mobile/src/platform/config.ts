export function nativeApiOrigin(): string {
  const value = process.env.EXPO_PUBLIC_FT_API_ORIGIN;
  if (!value) throw new Error("api_origin_invalid");
  let parsed: URL;
  try { parsed = new URL(value); } catch { throw new Error("api_origin_invalid"); }
  const localHttp = parsed.protocol === "http:" && parsed.hostname !== "" && parsed.port !== "" && process.env.NODE_ENV !== "production";
  const hostedHttps = parsed.protocol === "https:" && parsed.hostname !== "";
  const hasUnexpectedPath = parsed.pathname !== "/" && parsed.pathname !== "";
  if ((!localHttp && !hostedHttps) || parsed.username || parsed.password || hasUnexpectedPath || parsed.search || parsed.hash) {
    throw new Error("api_origin_invalid");
  }
  return value.replace(/\/$/, "");
}
