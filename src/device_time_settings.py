"""Проверяемые снимки настроек времени; адрес транспорта не служит идентичностью."""
from dataclasses import asdict, dataclass
import datetime
import hashlib
import json
from pathlib import Path
import re
import shlex

from time_store_lock import time_store_lock


class DeviceStateError(RuntimeError):
    def __init__(self, code, rollback=None):
        super().__init__(code)
        self.code = code
        self.rollback = rollback


def checked_shell(device, command, allow_help=False, preserve=False):
    # Legacy shell не сообщает exit code, а platform-tools transport намеренно
    # разрешает ненулевой exit для grep/help. Критичные операции проверяют свой.
    output = device.shell(command + "; printf '\\n__TVTF_EXIT__%s\\n' \"$?\"")
    if not isinstance(output, str) or len(output) > 1024 * 1024:
        raise DeviceStateError('state_read_failed')
    match = re.search(r'\n__TVTF_EXIT__(\d+)\s*$', output)
    if not match or int(match[1]) not in ((0, 255) if allow_help else (0,)):
        raise DeviceStateError('state_command_failed')
    body = output[:match.start()]
    if preserve:
        return body[:-2] if body.endswith('\r\n') else (body[:-1] if body.endswith('\n') else body)
    return body.strip()


@dataclass(frozen=True)
class TimeSettings:
    ntp_server: str
    auto_time: str
    auto_time_zone: str
    timezone: str
    effective_auto_zone: object = None

    @classmethod
    def from_dict(cls, values):
        if not isinstance(values, dict) or set(values) != {
                'ntp_server', 'auto_time', 'auto_time_zone', 'timezone', 'effective_auto_zone'}:
            raise DeviceStateError('state_invalid')
        state = cls(**values)
        if (not isinstance(state.ntp_server, str)
                or len(state.ntp_server) > 4096 or any(ord(c) < 32 for c in state.ntp_server)
                or state.auto_time not in ('0', '1', 'null')
                or state.auto_time_zone not in ('0', '1', 'null')
                or not isinstance(state.timezone, str)
                or not re.fullmatch(r'[A-Za-z0-9_+./-]{1,100}', state.timezone)
                or (state.effective_auto_zone is not None and type(state.effective_auto_zone) is not bool)):
            raise DeviceStateError('state_invalid')
        return state


def device_identity(device):
    props = {key: checked_shell(device, 'getprop ' + key) for key in (
        'ro.serialno', 'ro.boot.serialno', 'ro.product.manufacturer', 'ro.product.model', 'ro.product.device')}
    invalid = ('', 'unknown', 'null', '0', '0123456789abcdef')
    serial = next((props[key] for key in ('ro.serialno', 'ro.boot.serialno')
                   if props[key].lower() not in invalid and not set(props[key]) <= {'0'}), None)
    kind, value = 'serial', serial
    if not serial:
        kind = 'android_id'
        value = checked_shell(device, 'settings get secure android_id').lower()
        if (not re.fullmatch(r'[0-9a-f]{16}', value) or set(value) == {'0'}
                or value == '9774d56d682e549c'):
            raise DeviceStateError('state_identity_unavailable')
    if len(value) > 512 or any(ord(c) < 32 for c in value):
        raise DeviceStateError('state_identity_unavailable')
    material = [kind, value, props['ro.product.manufacturer'], props['ro.product.model'], props['ro.product.device']]
    digest = hashlib.sha256(json.dumps(material, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    return {'kind': kind, 'digest': digest}


def read_time_settings(device):
    values = {key: checked_shell(device, 'settings get global ' + key, preserve=key == 'ntp_server')
              for key in ('ntp_server', 'auto_time', 'auto_time_zone')}
    values['timezone'] = checked_shell(device, 'getprop persist.sys.timezone')
    sdk = checked_shell(device, 'getprop ro.build.version.sdk')
    if not sdk.isascii() or not sdk.isdigit() or int(sdk) <= 0:
        raise DeviceStateError('state_read_failed')
    effective = None
    if int(sdk) >= 31:
        help_text = checked_shell(device, 'cmd time_zone_detector help', allow_help=True)
        commands = ('is_auto_detection_enabled', 'set_auto_detection_enabled',
                    'is_telephony_detection_supported', 'is_geo_detection_supported')
        if all(re.search(r'^\s*' + name + r'(?:\s|$)', help_text, re.M) for name in commands):
            support = [checked_shell(device, 'cmd time_zone_detector ' + name) for name in commands[2:]]
            if any(value not in ('true', 'false') for value in support):
                raise DeviceStateError('state_read_failed')
            if 'true' in support:
                result = checked_shell(device, 'cmd time_zone_detector is_auto_detection_enabled')
                if result not in ('true', 'false'):
                    raise DeviceStateError('state_read_failed')
                effective = result == 'true'
    values['effective_auto_zone'] = effective
    return TimeSettings.from_dict(values)


def _check_restore_capacity(device, state):
    # Legacy ADB OPEN содержит всю shell-команду одним payload. Размер пакета
    # согласован при CNXN; современный platform-tools управляет им сам.
    limit = getattr(device, '_maxdata', None)
    if type(limit) is not int or limit <= 0:
        return
    command = 'settings put global ntp_server ' + shlex.quote(state.ntp_server)
    wrapped = command + "; printf '\\n__TVTF_EXIT__%s\\n' \"$?\""
    if len(('shell:' + wrapped + '\0').encode('utf-8')) > limit:
        raise DeviceStateError('state_transport_limit')


class TimeSnapshotStore:
    def __init__(self, directory, write_json):
        self.directory = Path(directory) / 'time-snapshots'
        self.write_json = write_json

    def _path(self, identity):
        if (not isinstance(identity, dict) or identity.get('kind') not in ('serial', 'android_id')
                or not isinstance(identity.get('digest'), str)
                or not re.fullmatch(r'[0-9a-f]{64}', identity['digest'])):
            raise DeviceStateError('state_identity_unavailable')
        return self.directory / (identity['digest'] + '.json')

    def load(self, identity):
        path = self._path(identity)
        if not path.exists():
            return None
        try:
            if path.stat().st_size > 32768:
                raise ValueError()
            data = json.loads(path.read_text(encoding='utf-8'))
            if data['version'] != 1 or data['identity'] != identity or not isinstance(data['saved_at'], str):
                raise ValueError()
            if datetime.datetime.fromisoformat(data['saved_at']).tzinfo is None:
                raise ValueError()
            TimeSettings.from_dict(data['settings'])
            return data
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise DeviceStateError('state_snapshot_invalid') from error

    def save(self, device, replace=False, expected_identity=None, expected_current=None):
        try:
            with time_store_lock(self.directory / '.lock'):
                return self._save_locked(device, replace, expected_identity, expected_current)
        except OSError as error:
            raise DeviceStateError('state_snapshot_write_failed') from error

    def _save_locked(self, device, replace, expected_identity, expected_current):
        identity = device_identity(device)
        if expected_identity is not None and identity != expected_identity:
            raise DeviceStateError('state_identity_changed')
        existing = self.load(identity)
        if existing is not None and not replace:
            _check_restore_capacity(device, TimeSettings.from_dict(existing['settings']))
            return existing
        state = read_time_settings(device)
        _check_restore_capacity(device, state)
        if expected_current is not None and state != expected_current:
            raise DeviceStateError('state_changed_before_apply')
        if read_time_settings(device) != state:
            raise DeviceStateError('state_changed_during_read')
        if device_identity(device) != identity:
            raise DeviceStateError('state_identity_changed')
        data = {'version': 1, 'identity': identity,
                'saved_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'settings': asdict(state)}
        try:
            self.write_json(self._path(identity), data)
        except OSError as error:
            raise DeviceStateError('state_snapshot_write_failed') from error
        return data


def _change(device, command, identity):
    if device_identity(device) != identity:
        raise DeviceStateError('state_identity_changed')
    return checked_shell(device, command)


def _write_global(device, name, value, identity):
    command = f'settings delete global {name}' if value == 'null' else f'settings put global {name} {shlex.quote(value)}'
    _change(device, command, identity)
    if checked_shell(device, 'settings get global ' + name, preserve=name == 'ntp_server') != value:
        raise DeviceStateError('state_readback_failed')


def _apply(device, desired, before, identity):
    zone_changed = desired.timezone != before.timezone
    if zone_changed:
        help_text = checked_shell(device, 'cmd alarm help', allow_help=True)
        if not re.search(r'^\s*set-timezone(?:\s|$)', help_text, re.M):
            raise DeviceStateError('state_timezone_unsupported')
    if desired.ntp_server != before.ntp_server:
        _write_global(device, 'ntp_server', desired.ntp_server, identity)
    if zone_changed:
        if before.effective_auto_zone is True:
            _change(device, 'cmd time_zone_detector set_auto_detection_enabled false', identity)
            if checked_shell(device, 'cmd time_zone_detector is_auto_detection_enabled') != 'false':
                raise DeviceStateError('state_readback_failed')
        elif before.effective_auto_zone is None and before.auto_time_zone == '1':
            _write_global(device, 'auto_time_zone', '0', identity)
        _change(device, 'cmd alarm set-timezone ' + shlex.quote(desired.timezone), identity)
        if checked_shell(device, 'getprop persist.sys.timezone') != desired.timezone:
            raise DeviceStateError('state_readback_failed')
    if desired.auto_time != before.auto_time:
        _write_global(device, 'auto_time', desired.auto_time, identity)
    if desired.effective_auto_zone is not None and (zone_changed or desired.effective_auto_zone != before.effective_auto_zone):
        _change(device, 'cmd time_zone_detector set_auto_detection_enabled ' + str(desired.effective_auto_zone).lower(), identity)
    if zone_changed or desired.auto_time_zone != before.auto_time_zone:
        _write_global(device, 'auto_time_zone', desired.auto_time_zone, identity)
    if read_time_settings(device) != desired:
        raise DeviceStateError('state_readback_failed')
    if device_identity(device) != identity:
        raise DeviceStateError('state_identity_changed')


def apply_time_settings(device, desired, expected_identity, expected_current=None):
    desired = TimeSettings.from_dict(asdict(desired))
    _check_restore_capacity(device, desired)
    if device_identity(device) != expected_identity:
        raise DeviceStateError('state_identity_changed')
    before = read_time_settings(device)
    _check_restore_capacity(device, before)
    if expected_current is not None and before != expected_current:
        raise DeviceStateError('state_changed_before_apply')
    if device_identity(device) != expected_identity:
        raise DeviceStateError('state_identity_changed')
    try:
        _apply(device, desired, before, expected_identity)
    except BaseException as error:
        # Android не предоставляет транзакцию для этих настроек. После любого
        # отказа пробуем вернуть состояние непосредственно перед применением.
        restored = False
        try:
            if device_identity(device) == expected_identity:
                _apply(device, before, read_time_settings(device), expected_identity)
                restored = True
        except Exception:
            pass
        if not isinstance(error, Exception):
            raise
        raise DeviceStateError('state_apply_failed', rollback=restored) from error
    return desired
