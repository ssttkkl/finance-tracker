const raw = process.env.EXPO_PUBLIC_FT_API_ORIGIN?.trim() ?? "";

function fail() {
  console.error("EXPO_PUBLIC_FT_API_ORIGIN must be a valid HTTPS origin");
  process.exit(1);
}

if (!raw) fail();

let url;
try {
  url = new URL(raw);
} catch {
  fail();
}

const hasNonRootPath = url.pathname !== "" && url.pathname !== "/";
if (url.protocol !== "https:" || !url.hostname || url.username || url.password || hasNonRootPath || url.search || url.hash) {
  fail();
}

console.log("EXPO_PUBLIC_FT_API_ORIGIN is configured for Native CI.");
