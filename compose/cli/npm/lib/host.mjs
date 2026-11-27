import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes } from 'node:crypto';
import { mkdirSync, lstatSync, fstatSync, closeSync, openSync, readSync, constants } from 'node:fs';
import { homedir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';

export function serverOrigin(value) {
    try {
        const url = new URL(value);
        if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) return null;
        if (url.protocol === 'https:' ||
            (url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname))) {
            return url.origin;
        }
    } catch (error) {
        if (!(error instanceof TypeError)) throw error;
    }
    return null;
}

const networkCodes = new Set([
    'ENETDOWN', 'ENETUNREACH', 'EHOSTDOWN', 'EHOSTUNREACH', 'ECONNREFUSED', 'ECONNRESET',
    'ETIMEDOUT', 'ENOTFOUND', 'EAI_AGAIN', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_SOCKET',
]);

function networkFailure(error, signal) {
    return signal.aborted || networkCodes.has(error.cause?.code);
}

function parseImportEnvelope(text) {
    return JSON.parse(text, (_, value, context) =>
        typeof value === 'number' ? JSON.rawJSON(context.source) : value);
}

function afterJsonString(text, start) {
    let index = start + 1;
    while (index < text.length) {
        const quote = text.indexOf('"', index);
        if (quote < 0) break;
        let backslashes = 0;
        while (text[quote - 1 - backslashes] === '\\') backslashes++;
        if (backslashes % 2 === 0) return quote + 1;
        index = quote + 1;
    }
    return -1;
}

export function splitLargeImportRequest(text, field, threshold) {
    if (text.length < threshold) return null;
    const owner = text.indexOf(`"${field}":{`);
    const requestKey = '"request":';
    const property = owner < 0 ? -1 : text.indexOf(requestKey, owner);
    if (property < 0) return null;
    const start = property + requestKey.length;
    if (text[start] !== '"') return null;
    const end = afterJsonString(text, start);
    if (end - start < threshold) return null;

    const placeholder = 'ft-embedded-import-request';
    const placeholderToken = JSON.stringify(placeholder);
    if (text.includes(placeholderToken)) return null;
    const head = text.slice(0, start);
    const tail = text.slice(end);
    const envelopeText = head + placeholderToken + tail;
    try {
        const envelope = parseImportEnvelope(envelopeText);
        // Only lift the exact field from a canonical envelope. Keep unusual inputs with the original typed decoder.
        if (envelope?.[field]?.request !== placeholder || JSON.stringify(envelope) !== envelopeText) return null;
        const request = JSON.parse(text.slice(start, end));
        return request.length < threshold ? null : { metadata: head + '""' + tail, request };
    } catch (error) {
        if (error instanceof SyntaxError) return null;
        throw error;
    }
}

export async function request(origin, method, path, token, body, suppliedHeaders) {
    const url = new URL(path, origin);
    if (url.origin !== origin) throw new Error('Invalid request origin');
    const headers = { Accept: 'application/json', ...suppliedHeaders };
    if (token !== null) headers.Authorization = `Bearer ${token}`;
    const bulk = url.pathname === '/api/v1/ledger/snapshot' || body instanceof FormData;
    const signal = AbortSignal.timeout(bulk ? 90000 : 8000);
    let response;
    try {
        response = await fetch(url, { method, headers, body, redirect: 'manual', signal });
        return { status: response.status, body: await response.text() };
    } catch (error) {
        if (response && !response.ok) return { status: response.status, body: '' };
        const diagnostic = error.cause?.code || error.name;
        if (networkFailure(error, signal)) return { status: 0, body: '', diagnostic };
        if (error instanceof TypeError && error.cause) return { status: -1, body: '', diagnostic };
        throw error;
    }
}

export function statementBody(metadata, ref, name, mediaType, bytes) {
    const body = new FormData();
    body.append('metadata', metadata);
    body.append(`file:${ref}`, new Blob([bytes], { type: mediaType }), name);
    return body;
}

export function timeZoneExists(zone) {
    try {
        new Intl.DateTimeFormat('en', { timeZone: zone });
        return true;
    } catch (error) {
        if (error instanceof RangeError) return false;
        throw error;
    }
}

export function timeZoneOffset(epochSeconds, zone) {
    return Temporal.Instant.fromEpochNanoseconds(BigInt(epochSeconds) * 1000000000n)
        .toZonedDateTimeISO(zone).offsetNanoseconds / 1000000000;
}

export function startOfDay(date, zone) {
    return Temporal.PlainDate.from(date).toZonedDateTime(zone).toInstant().toString();
}

function readKeychain(account, create) {
    const service = 'finance-tracker.cli';
    const keychain = '/usr/bin/security';
    const read = () => spawnSync(keychain, ['find-generic-password', '-s', service, '-a', account, '-w'], {
        encoding: 'utf8', timeout: 5000,
    });
    let result = read();
    if (result.status === 44 && create) {
        const secret = randomBytes(32).toString('base64');
        // Pass the secret through stdin instead of the process argument list.
        spawnSync(keychain, ['-i'], {
            input: `add-generic-password -s ${service} -a ${account} -w "${secret}"\n`,
            encoding: 'utf8',
            timeout: 5000,
        });
        result = read();
    }
    return result.status === 0 ? result.stdout.trim() : null;
}

function masterKey(origin, root, create) {
    const explicit = process.env.FT_CLI_STORAGE_KEY;
    delete process.env.FT_CLI_STORAGE_KEY;
    const value = explicit ?? (process.platform === 'darwin'
        ? readKeychain(createHash('sha256').update(root).update('\0').update(origin).digest('hex'), create)
        : null);
    if (!value || !/^[A-Za-z0-9+/]{43}=$/.test(value)) return null;
    const key = Buffer.from(value, 'base64');
    return key.length === 32 && key.toString('base64') === value ? key : null;
}

function privateFile(root) {
    mkdirSync(root, { mode: 0o700, recursive: true });
    const directory = lstatSync(root);
    if (!directory.isDirectory() || (process.getuid &&
        (directory.uid !== process.getuid() || (directory.mode & 0o077) !== 0))) {
        throw new Error('Unsafe state directory');
    }
    const filename = join(root, 'client.sqlite');
    const descriptor = openSync(filename, constants.O_CREAT | constants.O_RDWR | (constants.O_NOFOLLOW || 0), 0o600);
    closeSync(descriptor);
    const file = lstatSync(filename);
    if (!file.isFile() || (process.getuid && (file.uid !== process.getuid() || (file.mode & 0o077) !== 0))) {
        throw new Error('Unsafe state file');
    }
    return filename;
}

function seal(key, aad, payload) {
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(aad);
    const value = cipher.update(payload);
    const tail = cipher.final();
    return Buffer.concat([nonce, cipher.getAuthTag(), value, tail]);
}

const storageTextBlockUnits = 64 * 1024;

function* stateTextChunks(payload) {
    let start = 0;
    while (start < payload.length) {
        let end = Math.min(start + storageTextBlockUnits, payload.length);
        const last = payload.charCodeAt(end - 1);
        if (end < payload.length && last >= 0xd800 && last <= 0xdbff) end--;
        yield payload.slice(start, end);
        start = end;
    }
}

function sealStateChunks(key, aad, bytes, chunks) {
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(aad);
    const encrypted = Buffer.allocUnsafe(28 + bytes);
    nonce.copy(encrypted);
    let written = 28;
    for (const text of chunks) {
        const ciphertext = cipher.update(text, 'utf8');
        ciphertext.copy(encrypted, written);
        written += ciphertext.length;
    }
    const tail = cipher.final();
    tail.copy(encrypted, written);
    written += tail.length;
    if (written !== encrypted.length) throw new Error('Invalid encrypted state length');
    cipher.getAuthTag().copy(encrypted, nonce.length);
    return encrypted;
}

function sealStateText(key, aad, payload) {
    if (payload.length <= storageTextBlockUnits) return seal(key, aad, Buffer.from(payload));
    return sealStateChunks(key, aad, Buffer.byteLength(payload, 'utf8'), stateTextChunks(payload));
}

function escapedJsonStringBytes(text) {
    let bytes = Buffer.byteLength(text, 'utf8');
    for (let index = 0; index < text.length; index++) {
        const code = text.charCodeAt(index);
        if (code === 34 || code === 92 || code === 8 || code === 9 || code === 10 || code === 12 || code === 13) {
            bytes++;
        } else if (code < 32) {
            bytes += 5;
        } else if (code >= 0xd800 && code <= 0xdfff) {
            const next = text.charCodeAt(index + 1);
            if (code < 0xdc00 && next >= 0xdc00 && next <= 0xdfff) index++;
            else bytes += 3;
        }
    }
    return bytes;
}

function sealFrozenImportBatch(key, aad, metadata, request) {
    const envelope = parseImportEnvelope(metadata);
    if (envelope?.submission?.request !== '') throw new Error('Invalid frozen import metadata');
    const placeholder = '\u0000ft.import.request';
    const token = JSON.stringify(placeholder);
    envelope.submission.request = placeholder;
    const canonical = JSON.stringify(envelope);
    const position = canonical.indexOf(token);
    if (position < 0 || position !== canonical.lastIndexOf(token)) {
        envelope.submission.request = request;
        return sealStateText(key, aad, JSON.stringify(envelope));
    }
    function* chunks() {
        yield canonical.slice(0, position + 1);
        for (const text of stateTextChunks(request)) yield JSON.stringify(text).slice(1, -1);
        yield canonical.slice(position + token.length - 1);
    }
    const bytes = Buffer.byteLength(canonical, 'utf8') - token.length + 2 + escapedJsonStringBytes(request);
    return sealStateChunks(key, aad, bytes, chunks());
}

function open(key, aad, encrypted) {
    const value = Buffer.from(encrypted.buffer, encrypted.byteOffset, encrypted.byteLength);
    if (value.length < 28) throw new Error('Unrecognized ciphertext');
    const decipher = createDecipheriv('aes-256-gcm', key, value.subarray(0, 12));
    decipher.setAAD(aad);
    decipher.setAuthTag(value.subarray(12, 28));
    const plaintext = decipher.update(value.subarray(28));
    const tail = decipher.final();
    return tail.length === 0 ? plaintext : Buffer.concat([plaintext, tail]);
}

export function openStore(origin, createKey) {
    const root = resolve(process.env.FT_CLI_STATE_DIR || join(homedir(), '.finance-tracker-cli'));
    const database = new DatabaseSync(privateFile(root));
    let key;
    try {
        database.exec('PRAGMA busy_timeout = 10000; PRAGMA secure_delete = ON; BEGIN IMMEDIATE');
        const version = database.prepare('PRAGMA user_version').get().user_version;
        if (version > 1) {
            database.close();
            return { error: 'local_state_version_unsupported' };
        }
        database.exec(`
            CREATE TABLE IF NOT EXISTS client_state (
                server TEXT NOT NULL,
                slot TEXT NOT NULL,
                wrapped_key BLOB NOT NULL,
                payload BLOB NOT NULL,
                PRIMARY KEY (server, slot)
            ) STRICT;
            PRAGMA user_version = 1;
        `);
        const server = createHash('sha256').update(origin).digest('hex');
        const existing = database.prepare('SELECT 1 FROM client_state WHERE server = ? LIMIT 1').get(server);
        key = masterKey(origin, root, createKey && !existing);
        if (!key) {
            database.close();
            return { error: existing || createKey ? 'storage_key_unavailable' : 'authentication_required' };
        }
        const aad = (slot) => Buffer.from(`ft-client-v1\0${server}\0${slot}`);
        const writeState = (slot, encode) => {
            const dataKey = randomBytes(32);
            const associated = aad(slot);
            try {
                database.prepare('INSERT OR REPLACE INTO client_state VALUES (?, ?, ?, ?)').run(
                    server, slot, seal(key, associated, dataKey), encode(dataKey, associated),
                );
            } finally {
                dataKey.fill(0);
            }
        };
        return {
            workspaceSlot(token, email, workspace) {
                return createHmac('sha256', key).update(JSON.stringify([server, token, email, workspace])).digest('hex');
            },
            stateStamp(slot) {
                const item = database.prepare('SELECT wrapped_key FROM client_state WHERE server = ? AND slot = ?').get(server, slot);
                return item ? Buffer.from(item.wrapped_key).toString('base64') : null;
            },
            read(slot) {
                const item = database.prepare('SELECT wrapped_key, payload FROM client_state WHERE server = ? AND slot = ?').get(server, slot);
                if (!item) return null;
                const dataKey = open(key, aad(slot), item.wrapped_key);
                try {
                    return open(dataKey, aad(slot), item.payload).toString('utf8');
                } finally {
                    dataKey.fill(0);
                }
            },
            write(slot, payload) {
                writeState(slot, (key, aad) => sealStateText(key, aad, payload));
            },
            writeFrozenImportBatch(slot, metadata, request) {
                writeState(slot, (key, aad) => sealFrozenImportBatch(key, aad, metadata, request));
            },
            remove(slot) {
                database.prepare('DELETE FROM client_state WHERE server = ? AND slot = ?').run(server, slot);
            },
            slots(prefix) {
                return database.prepare('SELECT slot FROM client_state WHERE server = ? AND substr(slot, 1, length(?)) = ? ORDER BY slot')
                    .all(server, prefix, prefix).map((row) => row.slot);
            },
            removeByPrefix(prefix) {
                database.prepare('DELETE FROM client_state WHERE server = ? AND substr(slot, 1, length(?)) = ?').run(server, prefix, prefix);
            },
            clear() {
                database.prepare('DELETE FROM client_state WHERE server = ?').run(server);
            },
            dataVersion() {
                return database.prepare('PRAGMA data_version').get().data_version;
            },
            checkpoint() {
                database.exec('COMMIT; BEGIN IMMEDIATE');
            },
            close(commit) {
                try {
                    database.exec(commit ? 'COMMIT' : 'ROLLBACK');
                } finally {
                    key.fill(0);
                    database.close();
                }
            },
        };
    } catch (error) {
        key?.fill(0);
        database.close();
        if (error.errcode === 5 || error.errcode === 6) return { error: 'local_state_busy' };
        throw error;
    }
}

export function readStatement(path, limit) {
    let descriptor;
    try {
        descriptor = openSync(path, constants.O_RDONLY | constants.O_NONBLOCK);
        const stat = fstatSync(descriptor);
        if (!stat.isFile()) return { code: 'import_file_read_failed' };
        if (stat.size > limit) return { code: 'import_file_too_large' };
        const bytes = Buffer.alloc(stat.size + 1);
        let size = 0;
        while (size < bytes.length) {
            const read = readSync(descriptor, bytes, size, bytes.length - size, null);
            if (!read) break;
            size += read;
        }
        if (size > limit) return { code: 'import_file_too_large' };
        if (size !== stat.size) return { code: 'import_file_read_failed' };
        return {
            modifiedAt: Math.trunc(stat.mtimeMs).toString(),
            name: basename(path),
            bytes: new Int8Array(bytes.buffer, bytes.byteOffset, size),
        };
    } catch (error) {
        if (['ENOENT', 'ENOTDIR', 'EACCES', 'EPERM', 'EIO', 'EISDIR'].includes(error.code)) {
            return { code: 'import_file_read_failed' };
        }
        throw error;
    } finally {
        if (descriptor !== undefined) closeSync(descriptor);
    }
}
