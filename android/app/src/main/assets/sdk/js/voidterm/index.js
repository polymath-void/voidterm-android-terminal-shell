/**
 * VoidTerm Polyglot Extension SDK (Node.js)
 * Provides Promise-based async clients for Embedded OS API Gateway and NodeOS state.
 */

const fs = require('fs');
const net = require('net');
const http = require('http');
const path = require('path');
const { execSync } = require('child_process');

const PREFIX = process.env.PREFIX || '/data/user/0/com.hybridengine.terminal/files/usr';
const SOCK_PATH = path.join(PREFIX, 'var', 'run', 'void_api.sock');
const HTTP_PORT = 45671;

class VoidAPI {
    constructor(timeout = 3000) {
        this.timeout = timeout;
    }

    call(method, params = {}) {
        return new Promise((resolve, reject) => {
            const payload = JSON.stringify({
                method,
                params,
                id: Date.now()
            });

            // Try Unix domain socket first
            if (fs.existsSync(SOCK_PATH)) {
                const client = net.createConnection(SOCK_PATH, () => {
                    client.write(payload + '\n');
                });

                client.setTimeout(this.timeout);
                let buffer = '';

                client.on('data', (data) => {
                    buffer += data.toString();
                    if (buffer.indexOf('\n') !== -1) {
                        client.end();
                    }
                });

                client.on('end', () => {
                    try {
                        const parsed = JSON.parse(buffer.trim());
                        resolve(parsed.result || parsed);
                    } catch (e) {
                        resolve({ error: e.message });
                    }
                });

                client.on('error', () => {
                    this._callHttpFallback(payload, resolve);
                });
                return;
            }

            // HTTP Fallback
            this._callHttpFallback(payload, resolve);
        });
    }

    _callHttpFallback(payload, resolve) {
        const req = http.request({
            hostname: '127.0.0.1',
            port: HTTP_PORT,
            path: '/',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload)
            },
            timeout: this.timeout
        }, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(body);
                    resolve(parsed.result || parsed);
                } catch (e) {
                    resolve({ error: e.message });
                }
            });
        });

        req.on('error', (err) => {
            resolve({ error: `API Gateway connection error: ${err.message}` });
        });

        req.write(payload);
        req.end();
    }

    toast(text, long = false) {
        return this.call('toast.show', { text: String(text), long: Boolean(long) });
    }

    clipboardGet() {
        return this.call('clipboard.get').then(res => res.text || '');
    }

    clipboardSet(text) {
        return this.call('clipboard.set', { text: String(text) });
    }

    notify(title, text, id = 1002) {
        return this.call('notification.post', { title: String(title), text: String(text), id: Number(id) });
    }

    vibrate(durationMs = 200) {
        return this.call('vibrator.vibrate', { duration: Number(durationMs) });
    }

    battery() {
        return this.call('battery.status');
    }

    wakelockAcquire(tag = 'VoidTerm:JsLock', timeout = 300) {
        return this.call('power.wakelock_acquire', { tag: String(tag), timeout: Number(timeout) });
    }

    wakelockRelease(tag = 'VoidTerm:JsLock') {
        return this.call('power.wakelock_release', { tag: String(tag) });
    }

    kill(extId) {
        return this.call('process.kill', { ext_id: String(extId) });
    }

    killAll() {
        return this.call('process.kill_all');
    }
}

class NodeOS {
    static getDbPath() {
        const workspaceDb = '/data/data/com.termux/files/home/Projects/agy_nodeos.db';
        if (fs.existsSync(workspaceDb)) return workspaceDb;
        return path.join(PREFIX, 'agy_nodeos.db');
    }

    static isRunning(extId) {
        const dbPath = this.getDbPath();
        try {
            const out = execSync(`sqlite3 "${dbPath}" "SELECT active_pid FROM extension_runtime WHERE ext_id='${extId}';"`, { encoding: 'utf-8' }).trim();
            const pid = parseInt(out, 10);
            if (pid > 0 && fs.existsSync(`/proc/${pid}`)) {
                return { isRunning: true, pid };
            }
            return { isRunning: false, pid: 0 };
        } catch {
            return { isRunning: false, pid: 0 };
        }
    }
}

module.exports = {
    VoidAPI,
    NodeOS
};
