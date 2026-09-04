const PDF_SIGNATURE = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d]);
const ENCRYPT_ENTRY = new Uint8Array([0x2f, 0x45, 0x6e, 0x63, 0x72, 0x79, 0x70, 0x74]);
const TRAILER_ENTRY = new Uint8Array([0x74, 0x72, 0x61, 0x69, 0x6c, 0x65, 0x72]);
const MAX_PROBE_BYTES = 16 * 1024 * 1024;
const TRAILER_LOOKBACK_BYTES = 1024 * 1024;

function isWhitespace(value: number | undefined): boolean {
  return value === 0 || value === 9 || value === 10 || value === 12 || value === 13 || value === 32;
}

function isPdfDelimiter(value: number | undefined): boolean {
  return value === undefined || isWhitespace(value) || value === 0x25 || value === 0x28
    || value === 0x29 || value === 0x3c || value === 0x3e || value === 0x5b
    || value === 0x5d || value === 0x7b || value === 0x7d || value === 0x2f;
}

function startsWith(bytes: Uint8Array, prefix: Uint8Array): boolean {
  if (bytes.length < prefix.length) return false;
  return prefix.every((value, index) => bytes[index] === value);
}

function matchesTokenAt(bytes: Uint8Array, index: number, token: Uint8Array): boolean {
  if (index < 0 || index + token.length > bytes.length) return false;
  if (!token.every((value, offset) => bytes[index + offset] === value)) return false;
  return isPdfDelimiter(bytes[index - 1]) && isPdfDelimiter(bytes[index + token.length]);
}

function hasTrailerBefore(bytes: Uint8Array, index: number): boolean {
  const start = Math.max(0, index - TRAILER_LOOKBACK_BYTES);
  for (let cursor = index - TRAILER_ENTRY.length; cursor >= start; cursor -= 1) {
    if (matchesTokenAt(bytes, cursor, TRAILER_ENTRY)) return true;
  }
  return false;
}

function hasIndirectReference(bytes: Uint8Array, index: number): boolean {
  let cursor = index;
  if (bytes[cursor] === undefined || bytes[cursor] < 0x30 || bytes[cursor] > 0x39) return false;
  while (bytes[cursor] >= 0x30 && bytes[cursor] <= 0x39) cursor += 1;
  if (!isWhitespace(bytes[cursor])) return false;
  while (isWhitespace(bytes[cursor])) cursor += 1;
  if (bytes[cursor] === undefined || bytes[cursor] < 0x30 || bytes[cursor] > 0x39) return false;
  while (bytes[cursor] >= 0x30 && bytes[cursor] <= 0x39) cursor += 1;
  if (!isWhitespace(bytes[cursor])) return false;
  while (isWhitespace(bytes[cursor])) cursor += 1;
  return bytes[cursor] === 0x52 && isPdfDelimiter(bytes[cursor + 1]);
}

function hasEncryptEntry(bytes: Uint8Array): boolean {
  for (let index = 0; index <= bytes.length - ENCRYPT_ENTRY.length; index += 1) {
    if (!isPdfDelimiter(bytes[index - 1])) continue;
    if (!ENCRYPT_ENTRY.every((value, offset) => bytes[index + offset] === value)) continue;
    if (!hasTrailerBefore(bytes, index)) continue;
    const afterMarker = index + ENCRYPT_ENTRY.length;
    if (!isWhitespace(bytes[afterMarker])) continue;
    let valueStart = afterMarker;
    while (isWhitespace(bytes[valueStart])) valueStart += 1;
    if (hasIndirectReference(bytes, valueStart)) return true;
  }
  return false;
}

function readFileBytes(file: File): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === "function") return file.arrayBuffer();
  if (typeof FileReader !== "undefined") {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(reader.error ?? new Error("file_read_failed"));
      reader.onload = () => {
        if (reader.result instanceof ArrayBuffer) resolve(reader.result);
        else reject(new Error("file_read_failed"));
      };
      reader.readAsArrayBuffer(file);
    });
  }
  return new Response(file).arrayBuffer();
}

/**
 * Return true when a PDF contains the standard encryption trailer marker,
 * false for a known unencrypted/non-PDF file, and null when it cannot be
 * classified locally. The result is only a UI hint; the server remains the
 * authority for password validation.
 */
export async function detectPdfPasswordRequirement(file: File): Promise<boolean | null> {
  const isPdf = file.type === "application/pdf" || /\.pdf$/i.test(file.name);
  if (!isPdf) return false;
  if (file.size > MAX_PROBE_BYTES) return null;
  try {
    const bytes = new Uint8Array(await readFileBytes(file));
    if (!startsWith(bytes, PDF_SIGNATURE)) return null;
    return hasEncryptEntry(bytes);
  } catch {
    return null;
  }
}
