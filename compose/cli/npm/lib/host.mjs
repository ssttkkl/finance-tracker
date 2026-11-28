import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes } from 'node:crypto';
import { mkdirSync, mkdtempSync, lstatSync, fstatSync, closeSync, openSync, readSync, writeSync, fsyncSync, readdirSync, rmSync, constants } from 'node:fs';
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

class InvalidReadPage extends Error {}

async function readPageBody(response, limit) {
    const reader = response.body?.getReader();
    if (!reader) throw new InvalidReadPage();
    const encoding = response.headers.get('Content-Encoding')?.toLowerCase();
    const length = response.headers.get('Content-Length');
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let body = '';
    let bytes = 0;
    try {
        if (encoding && encoding !== 'gzip' && encoding !== 'identity') throw new InvalidReadPage();
        if ((encoding === 'gzip' && length === null) ||
            (length !== null && (!/^\d+$/.test(length) || !Number.isSafeInteger(Number(length)) || Number(length) > limit))) {
            throw new InvalidReadPage();
        }
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            bytes += value.byteLength;
            if (bytes > limit) throw new InvalidReadPage();
            try { body += decoder.decode(value, { stream: true }); }
            catch (error) { throw new InvalidReadPage('Invalid UTF-8 in read page', { cause: error }); }
        }
        try { return body + decoder.decode(); }
        catch (error) { throw new InvalidReadPage('Invalid UTF-8 in read page', { cause: error }); }
    } catch (error) {
        await reader.cancel().catch(() => {});
        throw error;
    } finally {
        reader.releaseLock();
    }
}

export async function request(origin, method, path, token, body, suppliedHeaders, maxResponseBytes = 0) {
    const url = new URL(path, origin);
    if (url.origin !== origin) throw new Error('Invalid request origin');
    const headers = { Accept: 'application/json', ...suppliedHeaders };
    if (token !== null) headers.Authorization = `Bearer ${token}`;
    const bulk = url.pathname === '/api/v1/ledger/snapshot' || body instanceof FormData;
    const signal = AbortSignal.timeout(bulk ? 90000 : 8000);
    let response;
    try {
        const options = { method, headers, body, redirect: 'manual', signal };
        if (method === 'POST' && url.pathname === '/api/v1/cash-import/write-set' &&
            typeof body === 'string' && body.length > storageTextBlockUnits) {
            headers['Content-Length'] = String(Buffer.byteLength(body, 'utf8'));
            options.body = streamUtf8(body);
            options.duplex = 'half';
        }
        response = await fetch(url, options);
        const readPages = response.headers.get('X-FT-Read-Pages');
        const text = maxResponseBytes > 0 ? await readPageBody(response, maxResponseBytes) : await response.text();
        return { status: response.status, body: text, readPages };
    } catch (error) {
        if (response && !response.ok) return { status: response.status, body: '' };
        if (error instanceof InvalidReadPage) return { status: -2, body: '' };
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

function streamUtf8(text) {
    const chunks = stateTextChunks(text);
    return new ReadableStream({
        pull(controller) {
            const next = chunks.next();
            if (next.done) controller.close();
            else controller.enqueue(Buffer.from(next.value, 'utf8'));
        },
        cancel() { chunks.return(); },
    });
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

function* generatedStateText(createCursor) {
    const next = createCursor();
    for (let part = next(); part !== null; part = next()) {
        if (typeof part !== 'string') throw new Error('Invalid encrypted state text');
        yield* stateTextChunks(part);
    }
}

function sealGeneratedState(key, aad, createCursor) {
    let bytes = 0;
    for (const part of generatedStateText(createCursor)) bytes += Buffer.byteLength(part, 'utf8');
    return sealStateChunks(key, aad, bytes, generatedStateText(createCursor));
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

function sqliteReviewBlocks(connection, ownsConnection) {
    connection.exec(`CREATE TABLE IF NOT EXISTS cash_review_work (
        generation TEXT NOT NULL,
        block INTEGER NOT NULL,
        part INTEGER NOT NULL,
        total INTEGER NOT NULL,
        payload BLOB NOT NULL,
        PRIMARY KEY (generation, block, part)
    ) STRICT, WITHOUT ROWID`);
    const insert = connection.prepare('INSERT INTO cash_review_work VALUES (?, ?, ?, ?, ?)');
    const select = connection.prepare('SELECT part, total, payload FROM cash_review_work WHERE generation = ? AND block = ? ORDER BY part');
    const remove = connection.prepare('DELETE FROM cash_review_work WHERE generation = ?');
    const generations = connection.prepare('SELECT DISTINCT generation FROM cash_review_work');
    return {
        removeAbandoned() {
            for (const row of generations.all()) {
                const owner = /^(\d+)-[0-9a-f]{32}$/.exec(row.generation);
                if (owner && !processAlive(Number(owner[1]))) remove.run(row.generation);
            }
        },
        write(generation, block, parts, encode) {
            const start = !connection.isTransaction;
            if (start) connection.exec('BEGIN IMMEDIATE');
            try {
                for (const [part, piece] of parts.entries())
                    insert.run(generation, block, part, parts.length, encode(piece, part, parts.length));
                if (start) connection.exec('COMMIT');
            } catch (error) {
                if (start && connection.isTransaction) connection.exec('ROLLBACK');
                throw error;
            }
        },
        read: (generation, block) => select.all(generation, block),
        close(generation) {
            if (ownsConnection) connection.close();
            else remove.run(generation);
        },
    };
}

function fileReviewBlocks(filename) {
    const descriptor = openSync(filename, 'wx+', 0o600);
    const index = new Map();
    let offset = 0;
    return {
        write(_, block, parts, encode) {
            if (index.has(block)) throw new Error('Cash review block must be UNIQUE');
            const positions = [];
            for (const [part, piece] of parts.entries()) {
                const bytes = encode(piece, part, parts.length);
                positions.push([offset, bytes.length]);
                let written = 0;
                while (written < bytes.length) {
                    const count = writeSync(descriptor, bytes, written, bytes.length - written, offset + written);
                    if (count === 0) throw new Error('Cash review block write did not progress');
                    written += count;
                }
                offset += bytes.length;
            }
            fsyncSync(descriptor);
            index.set(block, positions);
        },
        read(_, block) {
            const positions = index.get(block) ?? [];
            return positions.map(([offset, size], part) => {
                const payload = Buffer.alloc(size);
                let read = 0;
                while (read < size) {
                    const count = readSync(descriptor, payload, read, size - read, offset + read);
                    if (count === 0) throw new Error('Cash review block is incomplete');
                    read += count;
                }
                return { part, total: positions.length, payload };
            });
        },
        close() { closeSync(descriptor); },
    };
}

function processAlive(pid) {
    if (!Number.isSafeInteger(pid) || pid < 1 || pid > 2147483647) return true;
    try { process.kill(pid, 0); return true; }
    catch (error) {
        if (error.code === 'ESRCH') return false;
        if (error.code === 'EPERM') return true;
        throw error;
    }
}

function importWorksets(database, server, root) {
    const active = new Set();
    let shared;
    let checkedOrphans = false;
    return {
        open(batch, medium) {
            if (!['shared', 'sqlite', 'file'].includes(medium)) throw new Error('Invalid cash review workspace medium');
            if (!checkedOrphans) {
                for (const entry of readdirSync(root, { withFileTypes: true })) {
                    const owner = /^\.cash-review-(\d+)-[a-zA-Z0-9]{6}$/.exec(entry.name);
                    if (entry.isDirectory() && owner && !processAlive(Number(owner[1])))
                        rmSync(join(root, entry.name), { recursive: true, force: true });
                }
                checkedOrphans = true;
            }
            const generation = process.pid + '-' + randomBytes(16).toString('hex');
            const directory = medium === 'shared' ? null : mkdtempSync(join(root, '.cash-review-' + process.pid + '-'));
            let blocks;
            try {
                if (medium === 'shared') {
                    if (!shared) {
                        shared = sqliteReviewBlocks(database, false);
                        shared.removeAbandoned();
                    }
                    blocks = shared;
                }
                else if (medium === 'sqlite') {
                    const connection = new DatabaseSync(join(directory, 'work.sqlite'));
                    try { blocks = sqliteReviewBlocks(connection, true); }
                    catch (error) { connection.close(); throw error; }
                } else blocks = fileReviewBlocks(join(directory, 'blocks'));
            } catch (error) {
                if (directory != null) rmSync(directory, { recursive: true, force: true });
                throw error;
            }
            const key = randomBytes(32);
            const associated = (block, part, total) =>
                Buffer.from(JSON.stringify(['ft-cash-review-v1', server, batch, generation, block, part, total]));
            function requireBlock(block) {
                if (!active.has(workset)) throw new Error('Cash review workspace is closed');
                if (!Number.isSafeInteger(block) || block < 0) throw new Error('Invalid cash review block');
            }
            const workset = {
                write(block, text) {
                    requireBlock(block);
                    if (typeof text !== 'string') throw new Error('Invalid cash review block text');
                    const parts = [...stateTextChunks(text)];
                    if (parts.length === 0) parts.push('');
                    blocks.write(generation, block, parts, (piece, part, total) => {
                        const bytes = Buffer.from(piece);
                        try { return seal(key, associated(block, part, total), bytes); }
                        finally { bytes.fill(0); }
                    });
                },
                read(block) {
                    requireBlock(block);
                    const parts = blocks.read(generation, block);
                    if (parts.length === 0 || parts.some((row, part) => row.part !== part || row.total !== parts.length))
                        throw new Error('Cash review block is missing or incomplete');
                    return parts.map(({ part, payload }) => {
                        const bytes = open(key, associated(block, part, parts.length), payload);
                        try { return bytes.toString('utf8'); }
                        finally { bytes.fill(0); }
                    }).join('');
                },
                close() {
                    if (!active.delete(workset)) return;
                    key.fill(0);
                    try { blocks.close(generation); }
                    finally { if (directory != null) rmSync(directory, { recursive: true, force: true }); }
                },
            };
            active.add(workset);
            return workset;
        },
        requireClosed() {
            if (active.size !== 0) throw new Error('Open cash review workspace at checkpoint');
        },
        close() {
            for (const workset of active) workset.close();
        },
    };
}

export function openStore(origin, createKey) {
    const root = resolve(process.env.FT_CLI_STATE_DIR || join(homedir(), '.finance-tracker-cli'));
    const database = new DatabaseSync(privateFile(root));
    let key;
    try {
        database.exec('PRAGMA busy_timeout = 10000; PRAGMA secure_delete = ON; BEGIN IMMEDIATE');
        const worksets = importWorksets(database, origin, root);
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
        const writeState = (slot, encode, fingerprint = false) => {
            const dataKey = randomBytes(32);
            const associated = aad(slot);
            try {
                const payload = encode(dataKey, associated);
                database.prepare('INSERT OR REPLACE INTO client_state VALUES (?, ?, ?, ?)').run(
                    server, slot, seal(key, associated, dataKey), payload,
                );
                if (fingerprint) return createHash('sha256').update(payload).digest('hex');
            } finally {
                dataKey.fill(0);
            }
        };
        return {
            openImportWorkset(batch, medium = 'shared') {
                return worksets.open(batch, medium);
            },
            releaseImportRead() { database.exec('COMMIT'); },
            resumeImportWrite() { database.exec('BEGIN IMMEDIATE'); },
            workspaceSlot(token, email, workspace) {
                return createHmac('sha256', key).update(JSON.stringify([server, token, email, workspace])).digest('hex');
            },
            stateStamp(slot) {
                const item = database.prepare('SELECT wrapped_key FROM client_state WHERE server = ? AND slot = ?').get(server, slot);
                return item ? Buffer.from(item.wrapped_key).toString('base64') : null;
            },
            stateFingerprint(slot) {
                const item = database.prepare('SELECT payload FROM client_state WHERE server = ? AND slot = ?').get(server, slot);
                return item ? createHash('sha256').update(item.payload).digest('hex') : null;
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
            write(slot, payload, fingerprint = false) {
                return writeState(slot, (key, aad) => sealStateText(key, aad, payload), fingerprint);
            },
            writeGeneratedState(slot, createCursor) {
                writeState(slot, (key, aad) => sealGeneratedState(key, aad, createCursor));
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
                worksets.requireClosed();
                database.exec('COMMIT; BEGIN IMMEDIATE');
            },
            close(commit) {
                try {
                    if (!commit && database.isTransaction) database.exec('ROLLBACK');
                    worksets.close();
                    if (database.isTransaction) database.exec('COMMIT');
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
