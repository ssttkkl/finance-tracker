const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
const { test } = require('node:test');

test('the npm archive installs ft with help, version, and non-interactive validation', () => {
    const directory = mkdtempSync(join(tmpdir(), 'ft-entry-'));
    try {
        const dist = resolve(__dirname, '../build/npm');
        const [archive] = JSON.parse(execFileSync('npm', [
            'pack', '--json', '--pack-destination', directory, dist,
        ], { encoding: 'utf8' }));
        execFileSync('npm', [
            'install', '--no-audit', '--no-fund', '--ignore-scripts', join(directory, archive.filename),
        ], { cwd: directory, stdio: 'pipe' });

        const bin = join(directory, 'node_modules', '.bin', 'ft');
        const run = (...args) => spawnSync(bin, args, {
            cwd: directory,
            encoding: 'utf8',
            timeout: 5000,
            input: '',
        });

        const help = run('--help');
        assert.equal(help.status, 0, help.stderr);
        assert.match(help.stdout, /ft auth/);
        assert.match(run('auth', '--help').stdout, /--password-stdin/);

        const version = run('--version', '--json');
        assert.equal(version.status, 0, version.stderr);
        assert.equal(JSON.parse(version.stdout).version, archive.version);
        assert.equal(version.stderr, '');

        const missing = run('auth', 'login', '--json');
        assert.equal(missing.status, 2, missing.stderr);
        assert.deepEqual(JSON.parse(missing.stdout).error, {
            code: 'missing_parameter',
            parameter: '--email',
        });
        const empty = run('auth', 'login', '--email', 'test@example.test', '--password-stdin', '--json');
        assert.equal(empty.status, 2, empty.stderr);
        assert.equal(JSON.parse(empty.stdout).error.parameter, '--password-stdin');

        const secret = run('auth', 'login', '--password', 'example-secret', '--json');
        assert.equal(secret.status, 2, secret.stderr);
        assert.equal(JSON.parse(secret.stdout).error.code, 'unknown_option');
        assert.doesNotMatch(secret.stdout + secret.stderr, /example-secret/);
    } finally {
        rmSync(directory, { recursive: true, force: true });
    }
});
