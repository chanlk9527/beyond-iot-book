#!/usr/bin/env python3
"""macOS management/access/worker lifecycle using existing local dependencies."""
import argparse
import fcntl
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
import xml.etree.ElementTree as ET

import manage

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / 'backend'
RUN = ROOT / '.run/services'
PORTS = {'management': 8080, 'access': 8081, 'worker': 8082, 'access-b': 8083}


def directory(instance):
    return RUN / instance


def owned(instance):
    path = directory(instance) / 'process.mac.json'
    if not path.exists():
        return None
    state = json.loads(path.read_text())
    if state.get('started') and manage.identity(state['pid']) == state['started']:
        return state['pid']
    return None


def occupied(port):
    with socket.socket() as sock:
        return sock.connect_ex(('127.0.0.1', port)) == 0


def ready(instance):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        for suffix in ('', '/readiness'):
            with opener.open(f'http://127.0.0.1:{PORTS[instance]}/actuator/health{suffix}', timeout=2) as response:
                if response.status != 200 or json.load(response).get('status') != 'UP':
                    return False
        return True
    except (OSError, ValueError):
        return False


def stop(instance):
    pid = owned(instance)
    if pid:
        if os.getpgid(pid) != pid:
            raise ValueError(f'{instance}: process group changed; refusing to stop it.')
        os.killpg(pid, signal.SIGTERM)
        deadline = time.monotonic() + 15
        while owned(instance) and time.monotonic() < deadline:
            time.sleep(.2)
        if owned(instance):
            os.killpg(pid, signal.SIGKILL)
        print(f'{instance}: stopped', flush=True)
    (directory(instance) / 'process.mac.json').unlink(missing_ok=True)


def dependencies(env, timeout):
    if not shutil.which('docker'):
        raise ValueError('Docker Desktop is required. Install and launch it before starting the cluster.')
    def docker_ready():
        return subprocess.run(['docker', 'info'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not docker_ready():
        subprocess.run(['open', '-a', 'Docker'], check=True)
        print('Waiting for Docker Desktop...', flush=True)
        deadline = time.monotonic() + timeout
        while not docker_ready():
            if time.monotonic() >= deadline:
                raise ValueError('Docker Desktop did not become ready.')
            time.sleep(1)
    files = ['compose.kafka.yml']
    if env.get('HISTORY_PROVIDER') == 'tdengine':
        files.append('compose.tdengine.yml')
    for filename in files:
        subprocess.run(['docker', 'compose', '-f', str(ROOT / 'infra' / filename), 'up', '-d', '--wait', '--wait-timeout', str(timeout)], check=True)
    # PostgreSQL/MySQL and Redis use the connections from .env.local; never replace existing databases.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=['start', 'stop', 'restart', 'status'], default='start')
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--second-access', action='store_true')
    parser.add_argument('--services', nargs='+', choices=list(PORTS))
    parser.add_argument('--config', type=Path, default=BACKEND / '.env.local')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--skip-deps', action='store_true', help='Use already running dependencies without invoking Docker')
    parser.add_argument('--replace-combined', action='store_true', help='Stop the backend owned by manage.sh after preflight/build')
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('--migrate-out-of-order', action='store_true', help='Explicitly allow pending older Flyway migrations for management during this run')
    args = parser.parse_args()
    if not 5 <= args.timeout <= 600:
        parser.error('--timeout must be between 5 and 600 seconds.')
    if args.action in ('stop', 'status') and (args.build or args.replace_combined or args.validate_only or args.migrate_out_of_order):
        parser.error('Build, replacement and validation require start/restart.')
    names = args.services or (list(PORTS) if args.action in ('stop', 'status') else ['management', 'access', 'worker'])
    if args.second_access:
        names = list(names) + ['access-b']
    names = [name for name in PORTS if name in names]
    if args.migrate_out_of_order and 'management' not in names:
        parser.error('--migrate-out-of-order requires management in the selected services.')
    RUN.mkdir(parents=True, exist_ok=True)
    with (RUN / 'cluster.mac.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another cluster lifecycle operation is running.')
        if args.action == 'status':
            for name in names:
                state = ('ready' if ready(name) else 'running, not ready') if owned(name) else 'not managed/running'
                print(f'{name}: {state} (HTTP {PORTS[name]})')
            return
        if args.action == 'stop':
            for name in reversed(names):
                stop(name)
            print('Dependency containers remain running.')
            return
        env = manage.config(args.config)
        if not env.get('REDIS_URL', '').startswith(('redis://', 'rediss://')):
            raise ValueError('Three-service mode requires REDIS_URL in local configuration.')
        env.update(EVENTBUS_MODE='kafka', REDIS_ENABLED='true')
        env.pop('NEBULA_RUNTIME_ROLE', None)
        if not shutil.which('java'):
            raise ValueError('JDK 21 is required.')
        subprocess.run(['java', '-version'], check=True)
        version = ET.parse(BACKEND / 'pom.xml').getroot().find('{*}version').text
        artifacts = {name: BACKEND / f'platform-services/{"access" if name == "access-b" else name}-service/target/{"access" if name == "access-b" else name}-service-{version}-exec.jar' for name in names}
        combined = manage.owned('backend')
        if combined and not args.replace_combined:
            raise ValueError('manage.sh backend is running. Use --replace-combined to switch to three services.')
        if args.build and args.action != 'restart' and any(owned(n) for n in names):
            raise ValueError('Use restart --build to update running services.')
        for name in names:
            if not args.build and not artifacts[name].is_file():
                raise ValueError(f'{name}: artifact missing; use --build.')
            for port in [PORTS[name]] + ([1884 if name == 'access-b' else 1883] if name.startswith('access') else []):
                if occupied(port) and not owned(name) and not (combined and args.replace_combined and port in (8080, 1883)):
                    raise ValueError(f'Port {port} belongs to an unmanaged process; no process was stopped.')
        if args.validate_only:
            print('Cluster preflight passed; no services or containers changed.')
            return
        if args.build:
            subprocess.run(['mvn', '-B', '-ntp', '-s', '.mvn/settings-central.xml', '-pl', 'platform-services/service-verification', '-am', 'package', '-DskipTests'], cwd=BACKEND, check=True)
        if not args.skip_deps:
            dependencies(env, args.timeout)
        if combined:
            manage.stop('backend')
        if args.action == 'restart':
            for name in reversed(names):
                stop(name)
        started = []
        try:
            for name in names:
                if not owned(name):
                    role = 'access' if name == 'access-b' else name
                    node = 'access-b' if name == 'access-b' else role + '-a'
                    folder = directory(name)
                    folder.mkdir(parents=True, exist_ok=True)
                    runtime = folder / 'runtime' / str(time.time_ns())
                    runtime.mkdir(parents=True)
                    jar = runtime / f'{role}-service.jar'
                    shutil.copy2(artifacts[name], jar)
                    child_env = dict(env)
                    if role != 'management':
                        for key in list(child_env):
                            if key.startswith('NEBULA_MANAGEMENT_INITIAL_ACCOUNT_'):
                                child_env.pop(key)
                    if role == 'access':
                        child_env['HISTORY_PROVIDER'] = 'disabled'
                    command = ['java', '-Xmx512m', '-jar', str(jar), '--server.address=127.0.0.1', f'--server.port={PORTS[name]}', f'--nebula.runtime.role={role}', '--nebula.eventbus.mode=kafka', f'--nebula.eventbus.node-id={node}', f'--nebula.access.node-name={node}', '--nebula.redis.enabled=true']
                    if role == 'management' and args.migrate_out_of_order:
                        command.append('--spring.flyway.out-of-order=true')
                    if role == 'access':
                        command += ['--nebula.mqtt.host=127.0.0.1', f'--nebula.mqtt.port={1884 if name == "access-b" else 1883}']
                    with (folder / 'stdout.log').open('w') as out, (folder / 'stderr.log').open('w') as err:
                        process = subprocess.Popen(command, cwd=BACKEND, env=child_env, stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True)
                    started.append(name)
                    stamp = manage.identity(process.pid)
                    if not stamp:
                        raise ValueError(f'{name} exited before process registration; check {folder}.')
                    (folder / 'process.mac.json').write_text(json.dumps({'pid': process.pid, 'started': stamp, 'httpPort': PORTS[name], 'jar': str(jar)}))
                    print(f'{name}: launched (PID {process.pid})', flush=True)
                deadline = time.monotonic() + args.timeout
                while not ready(name):
                    if not owned(name):
                        raise ValueError(f'{name} exited; check {directory(name)}/stdout.log and stderr.log.')
                    if time.monotonic() >= deadline:
                        raise ValueError(f'{name} timed out; check {directory(name)}/stdout.log and stderr.log.')
                    time.sleep(.5)
                print(f'{name}: ready (HTTP {PORTS[name]})', flush=True)
        except BaseException:
            for name in reversed(started):
                stop(name)
            raise
        print('Cluster ready. Management: http://127.0.0.1:8080 ; MQTT: 127.0.0.1:1883')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('Error: ' + str(error), file=sys.stderr)
        sys.exit(1)
