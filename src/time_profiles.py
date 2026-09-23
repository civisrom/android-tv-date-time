"""Именованные конфигурации времени, привязанные к проверенному устройству."""
from dataclasses import asdict
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

from device_time_settings import DeviceStateError, TimeSettings
from time_store_lock import time_store_lock


def parse_ntp_configuration(raw, validate_host):
    """Возвращает endpoints без изменения исходной строки снимка."""
    if raw in ('null', ''):
        return []
    if not isinstance(raw, str) or not raw or len(raw) > 4096:
        raise DeviceStateError('state_ntp_invalid')
    endpoints = []
    for value in raw.split('|'):
        if value.startswith('ntp://'):
            try:
                parsed = urlsplit(value)
                host, port = parsed.hostname, 123 if parsed.port is None else parsed.port
                if (parsed.scheme != 'ntp' or parsed.username is not None or parsed.password is not None
                        or parsed.path or parsed.query or parsed.fragment):
                    raise ValueError()
            except ValueError as error:
                raise DeviceStateError('state_ntp_invalid') from error
        else:
            host, port = value, 123
        if not host or not validate_host(host) or not 1 <= port <= 65535:
            raise DeviceStateError('state_ntp_invalid')
        endpoints.append((host, port))
    return endpoints


def build_ntp_configuration(hosts, sdk, validate_host):
    if not 1 <= len(hosts) <= 4 or any(not validate_host(host) for host in hosts):
        raise DeviceStateError('state_ntp_invalid')
    if len({host.lower() for host in hosts}) != len(hosts):
        raise DeviceStateError('state_ntp_invalid')
    if sdk < 34:
        if len(hosts) > 1:
            raise DeviceStateError('state_multi_unsupported')
        return hosts[0]
    return '|'.join('ntp://' + ('[' + host + ']' if ':' in host else host) for host in hosts)


class TimeProfileStore:
    def __init__(self, directory, write_json):
        self.path = Path(directory) / 'time-profiles.json'
        self.write_json = write_json

    @staticmethod
    def _validate(profile):
        if (not isinstance(profile, dict) or set(profile) != {'name', 'target', 'identity', 'settings'}
                or not isinstance(profile['name'], str) or not 1 <= len(profile['name']) <= 80
                or not profile['name'].strip() or not profile['name'].isprintable()
                or not isinstance(profile['target'], str) or not profile['target'].isprintable()
                or len(profile['target']) > 300
                or not isinstance(profile['identity'], dict)
                or profile['identity'].get('kind') not in ('serial', 'android_id')):
            raise DeviceStateError('state_profile_invalid')
        if (not isinstance(profile['identity'].get('digest'), str)
                or not re.fullmatch(r'[0-9a-f]{64}', profile['identity']['digest'])):
            raise DeviceStateError('state_profile_invalid')
        TimeSettings.from_dict(profile['settings'])
        return profile

    def load(self):
        if not self.path.exists():
            return []
        try:
            if self.path.stat().st_size > 1024 * 1024:
                raise ValueError()
            data = json.loads(self.path.read_text(encoding='utf-8'))
            if data['version'] != 1 or not isinstance(data['profiles'], list):
                raise ValueError()
            profiles = [self._validate(profile) for profile in data['profiles']]
            if len(profiles) > 64 or len({profile['name'] for profile in profiles}) != len(profiles):
                raise ValueError()
            return profiles
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise DeviceStateError('state_profile_invalid') from error

    def save(self, name, target, identity, settings, replace=False):
        with time_store_lock(self.path.with_suffix('.lock')):
            return self._save_locked(name, target, identity, settings, replace)

    def _save_locked(self, name, target, identity, settings, replace):
        profile = self._validate({'name': name, 'target': target, 'identity': identity, 'settings': asdict(settings)})
        profiles = self.load()
        existing = next((item for item in profiles if item['name'] == name), None)
        if existing is not None and not replace:
            raise DeviceStateError('state_profile_exists')
        profiles = [item for item in profiles if item['name'] != name] + [profile]
        data = {'version': 1, 'profiles': profiles}
        if len(profiles) > 64 or len(json.dumps(data, ensure_ascii=False, indent=2).encode('utf-8')) > 1024 * 1024:
            raise DeviceStateError('state_profile_limit')
        self.write_json(self.path, data)
        return profile

    def delete(self, name):
        with time_store_lock(self.path.with_suffix('.lock')):
            self._delete_locked(name)

    def _delete_locked(self, name):
        profiles = self.load()
        retained = [profile for profile in profiles if profile['name'] != name]
        if len(retained) != len(profiles):
            self.write_json(self.path, {'version': 1, 'profiles': retained})
