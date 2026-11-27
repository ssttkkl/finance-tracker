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
    const value = Buffer.concat([cipher.update(payload), cipher.final()]);
    return Buffer.concat([nonce, cipher.getAuthTag(), value]);
}

function open(key, aad, encrypted) {
    const value = Buffer.from(encrypted);
    if (value.length < 28) throw new Error('Unrecognized ciphertext');
    const decipher = createDecipheriv('aes-256-gcm', key, value.subarray(0, 12));
    decipher.setAAD(aad);
    decipher.setAuthTag(value.subarray(12, 28));
    return Buffer.concat([decipher.update(value.subarray(28)), decipher.final()]);
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
        return {
            workspaceSlot(token, email, workspace) {
                return createHmac('sha256', key).update(JSON.stringify([server, token, email, workspace])).digest('hex');
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
                const dataKey = randomBytes(32);
                try {
                    database.prepare('INSERT OR REPLACE INTO client_state VALUES (?, ?, ?, ?)').run(
                        server, slot, seal(key, aad(slot), dataKey), seal(dataKey, aad(slot), Buffer.from(payload)),
                    );
                } finally {
                    dataKey.fill(0);
                }
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
