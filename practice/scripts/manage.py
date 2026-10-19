#!/usr/bin/env python3
"""Local macOS service lifecycle. Requires only Python's standard library."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / '.run'
JAR = ROOT / 'backend/platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT.jar'
VITE = ROOT / 'console/node_modules/vite/bin/vite.js'
PORTS = {'backend': 8080, 'frontend': 5173}
ALLOWED = set('DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD REDIS_ENABLED REDIS_URL REDIS_COMMAND_TIMEOUT_MS MQTT_ENABLED MQTT_PORT NEBULA_MANAGEMENT_INITIAL_ACCOUNT_USERNAME NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PASSWORD NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN'.split())


def identity(pid):
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'stat=', '-o', 'lstart='], capture_output=True, text=True)
    parts = result.stdout.strip().split(None, 1)
    if result.returncode or len(parts) != 2 or parts[0].startswith('Z'):
        return None
    return parts[1]


def owned(name):
    path = RUN / (name + '.mac.json')
    if not path.exists():
        return None
    state = json.loads(path.read_text())
    return state['pid'] if identity(state['pid']) == state['started'] else None


def stop(name):
    pid = owned(name)
    if pid:
        os.killpg(pid, signal.SIGTERM)
        for _ in range(100):
            if not owned(name):
                break
            time.sleep(.1)
        if owned(name):
            os.killpg(pid, signal.SIGKILL)
    (RUN / (name + '.mac.json')).unlink(missing_ok=True)
    print(name + ': stopped', flush=True)


def ready(name):
    url = 'http://127.0.0.1:' + str(PORTS[name])
    if name == 'backend':
        url += '/actuator/health'
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=2) as response:
            return response.status == 200 and (name != 'backend' or json.load(response).get('status') == 'UP')
    except (OSError, ValueError):
        return False


def config(path):
    if not path.is_file():
        raise ValueError('Copy backend/.env.example to backend/.env.local and fill it in.')
    settings = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        key = key.strip()
        if not sep or key not in ALLOWED or key in settings:
            raise ValueError('Invalid, unsupported or duplicate configuration key (values hidden).')
        if not value.strip() or '__FILL_' in value or 'YOUR_DATABASE_HOST' in value:
            raise ValueError('Fill in ' + key + ' in backend/.env.local.')
        settings[key] = value
    for key in ('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD'):
        if key not in settings:
            raise ValueError('Missing ' + key)
    if not settings['DATABASE_URL'].startswith('jdbc:postgresql://'):
        raise ValueError('DATABASE_URL must be a PostgreSQL JDBC URL.')
    for key in ('REDIS_ENABLED', 'MQTT_ENABLED', 'NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN'):
        if key in settings and settings[key] not in ('true', 'false'):
            raise ValueError(key + ' must be true or false.')
    if 'MQTT_PORT' in settings and not 1 <= int(settings['MQTT_PORT']) <= 65535:
        raise ValueError('MQTT_PORT must be between 1 and 65535.')
    env = dict(os.environ, **settings)
    if env.get('REDIS_ENABLED') == 'true' and not env.get('REDIS_URL', '').startswith(('redis://', 'rediss://')):
        raise ValueError('Redis requires redis:// or rediss:// URL.')
    user = settings.get('NEBULA_MANAGEMENT_INITIAL_ACCOUNT_USERNAME')
    password = settings.get('NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PASSWORD')
    if bool(user) != bool(password) or (user and (len(user) > 100 or len(password.encode()) > 72)):
        raise ValueError('Supply both initial account settings; username max 100 characters, password max 72 UTF-8 bytes.')
    if 'NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN' in settings and not user:
        raise ValueError('Platform admin initialization requires initial account settings.')
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=['start', 'stop', 'restart', 'status'], default='start')
    parser.add_argument('--build', action='store_true', help='Build backend JAR (skip tests)')
    parser.add_argument('--install', action='store_true', help='Install frontend dependencies using npm ci')
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--backend-only', action='store_true')
    group.add_argument('--frontend-only', action='store_true')
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('--timeout', type=int, default=90)
    parser.add_argument('--config', type=Path, default=ROOT / 'backend/.env.local')
    args = parser.parse_args()
    names = ['backend'] if args.backend_only else ['frontend'] if args.frontend_only else list(PORTS)
    if not 5 <= args.timeout <= 600:
        parser.error('--timeout must be between 5 and 600 seconds.')
    if (args.build and 'backend' not in names) or (args.install and 'frontend' not in names) or ((args.build or args.install) and args.action in ('stop', 'status')):
        parser.error('Build/install flags must match the selected services and start/restart action.')
    if args.action == 'status':
        for name in names:
            print(name + ': ' + ('ready' if ready(name) else 'running, not ready') if owned(name) else name + ': not managed/running')
        return
    if args.action in ('stop', 'restart') and not args.validate_only:
        for name in names:
            stop(name)
        if args.action == 'stop':
            return
    env = dict(os.environ)
    if 'backend' in names:
        env = config(args.config)
        if not shutil.which('java'):
            raise ValueError('JDK 21 is required.')
        subprocess.run(['java', '-version'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        if args.build and not shutil.which('mvn'):
            raise ValueError('Maven is required for --build.')
        if not args.build and not JAR.is_file():
            raise ValueError('Backend JAR missing. Run with --build.')
    if 'frontend' in names:
        if not shutil.which('node'):
            raise ValueError('Node.js 24 is required.')
        if args.install and not shutil.which('npm'):
            raise ValueError('npm is required for --install.')
        if not args.install and not VITE.is_file():
            raise ValueError('Frontend dependencies missing. Run with --install.')
    for name in names:
        if owned(name):
            if (name == 'backend' and args.build) or (name == 'frontend' and args.install):
                raise ValueError('Use restart before building/installing a running service.')
            continue
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1', PORTS[name])) == 0:
                raise ValueError('Port ' + str(PORTS[name]) + ' occupied by an unmanaged process.')
    if args.validate_only:
        print('Preflight passed; no changes performed.')
        return
    if args.build:
        subprocess.run(['mvn', '-B', '-ntp', '-s', '.mvn/settings-central.xml', '-pl', 'platform-bootstrap', '-am', '-DskipTests', 'package'], cwd=ROOT / 'backend', check=True)
    if args.install:
        subprocess.run(['npm', 'ci', '--no-audit', '--no-fund'], cwd=ROOT / 'console', check=True)
    RUN.mkdir(exist_ok=True)
    started = []
    try:
        for name in names:
            if owned(name):
                continue
            if name == 'backend':
                runtime = RUN / 'backend-runtime' / str(time.time_ns())
                runtime.mkdir(parents=True)
                archive = runtime / 'platform-bootstrap.jar'
                shutil.copy2(JAR, archive)
                command = ['java', '-jar', str(archive), '--spring.profiles.active=management', '--server.address=127.0.0.1', '--server.port=8080']
            else:
                command = ['node', str(VITE), '--host', '127.0.0.1', '--port', '5173', '--strictPort']
            with (RUN / (name + '.log')).open('w') as out, (RUN / (name + '.err.log')).open('w') as err:
                process = subprocess.Popen(command, cwd=ROOT / ('backend' if name == 'backend' else 'console'), env=env if name == 'backend' else dict(os.environ, API_PROXY_TARGET='http://127.0.0.1:8080'), stdout=out, stderr=err, stdin=subprocess.DEVNULL, start_new_session=True)
            (RUN / (name + '.mac.json')).write_text(json.dumps({'pid': process.pid, 'started': identity(process.pid)}))
            started.append(name)
            print(name + ': launched (PID ' + str(process.pid) + ')', flush=True)
        deadline = time.monotonic() + args.timeout
        pending = set(names)
        while pending:
            for name in list(pending):
                if not owned(name):
                    raise ValueError(name + ' exited; check .run/' + name + '.log and .err.log')
                if ready(name):
                    pending.remove(name)
                    print(name + ': ready', flush=True)
            if pending and time.monotonic() >= deadline:
                raise ValueError('Startup timed out; check .run logs and database/Redis connection.')
            if pending:
                time.sleep(.4)
    except BaseException:
        for name in started:
            stop(name)
        raise
    print('Console: http://127.0.0.1:5173 ; API: http://127.0.0.1:8080')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('Error: ' + str(error), file=sys.stderr)
        sys.exit(1)
