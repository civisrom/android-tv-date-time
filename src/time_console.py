"""Снимки, профили и проверка часов в существующем консольном интерфейсе."""
from dataclasses import asdict, replace
import datetime
from pathlib import Path
import re
import sys

from colorama import Fore
from locales import locales
from device_time_check import monitor_device_time, verify_device_time
from device_time_settings import (DeviceStateError, TimeSettings, TimeSnapshotStore, apply_time_settings,
                                  checked_shell, device_identity, read_time_settings)
from time_profiles import TimeProfileStore, build_ntp_configuration, parse_ntp_configuration


def snapshot_store(fixer):
    return TimeSnapshotStore(fixer.data_dir, fixer._atomic_write_json)


def require_device(fixer):
    if fixer.device is None:
        raise DeviceStateError('no_device_connected')
    return fixer.device


def _references(fixer, raw):
    try:
        endpoints = parse_ntp_configuration(raw, fixer.validate_ntp_server)
        # Существующие URI с нестандартным портом сохраняются в снимках точно.
        # Контрольный SNTP-клиент здесь использует только штатный UDP/123.
        return [host for host, port in endpoints if port == 123][:4]
    except DeviceStateError:
        return []


def clock_check(fixer, raw=None):
    device = require_device(fixer)
    if raw is None:
        raw = checked_shell(device, 'settings get global ntp_server')
    return verify_device_time(lambda command, timeout: fixer._timed_device_shell(device, command, timeout),
                              _references(fixer, raw))


def print_clock_check(check):
    print(locales.get('state_clock_' + check.status_at().lower()))
    if check.difference_seconds is not None:
        print(locales.get('state_clock_measurement', difference=f'{check.difference_seconds:+.3f}',
                         uncertainty=f'{check.uncertainty_seconds:.3f}'))
    if check.reference_server:
        print(locales.get('state_clock_reference', server=check.reference_server))
    print(locales.get('state_source_unconfirmed'))


def show_time_status(fixer, raw=None):
    try:
        check = clock_check(fixer, raw)
        print_clock_check(check)
        return check
    except Exception:
        print(locales.get('state_clock_device_unavailable'))
        print(locales.get('state_source_unconfirmed'))
        return None


def _preview(before, desired):
    for name in ('ntp_server', 'auto_time', 'auto_time_zone', 'timezone'):
        print(locales.get('state_preview_field', field=name, before=getattr(before, name), after=getattr(desired, name)))
    if before.effective_auto_zone is not None or desired.effective_auto_zone is not None:
        print(locales.get('state_preview_field', field='effective_auto_zone', before=before.effective_auto_zone,
                         after=desired.effective_auto_zone))


def _confirmed():
    return input(locales.get('state_apply_confirm')).strip().lower() == 'yes'


def _snapshot(fixer, restore=False):
    device = require_device(fixer)
    store = snapshot_store(fixer)
    identity = device_identity(device)
    existing = store.load(identity)
    current = read_time_settings(device)
    if restore:
        if existing is None:
            raise DeviceStateError('state_snapshot_missing')
        print(locales.get('state_snapshot_date', date=existing['saved_at']))
        desired = TimeSettings.from_dict(existing['settings'])
        _preview(current, desired)
        if _confirmed():
            apply_time_settings(device, desired, identity, expected_current=current)
            print(Fore.GREEN + locales.get('state_restore_success'))
            show_time_status(fixer, desired.ntp_server)
    else:
        _preview(current, current)
        if existing is not None:
            print(locales.get('state_snapshot_replace', date=existing['saved_at']))
        if _confirmed():
            store.save(device, replace=True, expected_identity=identity, expected_current=current)
            print(Fore.GREEN + locales.get('state_snapshot_saved'))


def _read_api(device):
    value = checked_shell(device, 'getprop ro.build.version.sdk')
    if not value.isascii() or not value.isdigit() or int(value) <= 0:
        raise DeviceStateError('state_read_failed')
    return int(value)


def _enter_ntp(fixer, device, default):
    answer = input(locales.get('state_multi_prompt')).strip()
    if not answer:
        return default
    hosts = re.split(r'[\s,]+', answer)
    return build_ntp_configuration(hosts, _read_api(device), fixer.validate_ntp_server)


def _set_multiple(fixer):
    device = require_device(fixer)
    identity = device_identity(device)
    before = checked_shell(device, 'settings get global ntp_server', preserve=True)
    raw = _enter_ntp(fixer, device, before)
    if raw == before:
        return
    print(locales.get('state_preview_field', field='ntp_server', before=before, after=raw))
    print(locales.get('state_multi_policy'))
    if not _confirmed():
        return
    if not _verify_configuration(fixer, raw):
        return
    fixer._write_ntp_setting(raw, expected_current=before, expected_identity=identity)


def _verify_configuration(fixer, raw):
    unverified_allowed = False
    for host, _port in parse_ntp_configuration(raw, fixer.validate_ntp_server):
        verified = _port == 123 and fixer.verify_ntp_server(host)
        if not verified and not unverified_allowed:
            if input(locales.get('ntp_unverified_confirm')).strip().lower() != 'yes':
                return False
            unverified_allowed = True
    return True


def _select_profile(store):
    profiles = store.load()
    for index, profile in enumerate(profiles, 1):
        print(f'{index}. {profile["name"]} — {profile["target"]}')
    if not profiles:
        raise DeviceStateError('state_profiles_empty')
    answer = input(locales.get('state_profile_select')).strip()
    if not answer:
        return None
    if not answer.isascii() or not answer.isdigit() or not 1 <= int(answer) <= len(profiles):
        raise DeviceStateError('invalid_input')
    return profiles[int(answer) - 1]


def _profiles(fixer):
    store = TimeProfileStore(fixer.data_dir, fixer._atomic_write_json)
    while True:
        print(locales.get('state_profiles_menu'))
        action = input(locales.get('select_action')).strip()
        if action == '0':
            return
        if action == '1':
            for profile in store.load():
                print(f'{profile["name"]} — {profile["target"]}')
        elif action == '2':
            device = require_device(fixer)
            name = input(locales.get('state_profile_name')).strip()
            identity = device_identity(device)
            target = fixer.connected_ip or ''
            current = read_time_settings(device)
            if device_identity(device) != identity:
                raise DeviceStateError('state_identity_changed')
            raw = _enter_ntp(fixer, device, current.ntp_server)
            values = asdict(current)
            for key in ('auto_time', 'auto_time_zone', 'timezone'):
                answer = input(locales.get('state_profile_field', field=key, current=values[key])).strip()
                if answer:
                    values[key] = answer
                    if key == 'auto_time_zone' and values['effective_auto_zone'] is not None:
                        if answer not in ('0', '1'):
                            raise DeviceStateError('state_invalid')
                        values['effective_auto_zone'] = answer == '1'
            desired = replace(TimeSettings.from_dict(values), ntp_server=raw)
            _preview(current, desired)
            if any(profile['name'] == name for profile in store.load()):
                print(locales.get('state_profile_overwrite'))
            if _confirmed():
                if device_identity(device) != identity:
                    raise DeviceStateError('state_identity_changed')
                store.save(name, target, identity, desired, replace=True)
                print(locales.get('state_profile_saved'))
        elif action in ('3', '4'):
            profile = _select_profile(store)
            if profile is None:
                continue
            if action == '4':
                if _confirmed():
                    store.delete(profile['name'])
                continue
            target = profile['target']
            if not fixer.validate_device_target(target):
                raise DeviceStateError('state_profile_invalid')
            if target.startswith('usb:'):
                # USB transport ID меняется при перезапуске adb. Пользователь
                # сначала выбирает устройство, затем проверяется его identity.
                device = require_device(fixer)
            else:
                fixer.connect_or_reuse(target)
                device = require_device(fixer)
            if device_identity(device) != profile['identity']:
                raise DeviceStateError('state_identity_changed')
            current = read_time_settings(device)
            desired = TimeSettings.from_dict(profile['settings'])
            endpoints = parse_ntp_configuration(desired.ntp_server, fixer.validate_ntp_server)
            if _read_api(device) < 34 and (len(endpoints) > 1 or desired.ntp_server.startswith('ntp://')):
                raise DeviceStateError('state_multi_unsupported')
            _preview(current, desired)
            if _confirmed():
                if not _verify_configuration(fixer, desired.ntp_server):
                    continue
                snapshot_store(fixer).save(device)
                apply_time_settings(device, desired, profile['identity'], expected_current=current)
                print(Fore.GREEN + locales.get('state_restore_success'))
                show_time_status(fixer, desired.ntp_server)
        else:
            print(locales.get('invalid_input'))


def diagnostic_report(fixer):
    # Только заранее определённые поля. Ни get_device_info(), ни логи/снимки,
    # ни shell output не попадают в экспорт даже при ошибке устройства.
    report = {'schema_version': 1, 'app_version': '2.6.5', 'platform': sys.platform,
              'exported_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'transport_selected': bool(fixer.device), 'android_api': None, 'settings_read': False,
              'read_failure': None,
              'automatic_time': None, 'automatic_timezone': None,
              'ntp_configuration': 'unknown', 'clock_status': 'DEVICE_UNAVAILABLE',
              'clock_difference_seconds': None, 'clock_uncertainty_seconds': None,
              'system_clock_source': 'unconfirmed'}
    if fixer.device is not None:
        try:
            state = read_time_settings(fixer.device)
            report.update(android_api=_read_api(fixer.device), settings_read=True,
                          automatic_time={'0': False, '1': True}.get(state.auto_time),
                          automatic_timezone=state.effective_auto_zone if state.effective_auto_zone is not None
                          else {'0': False, '1': True}.get(state.auto_time_zone),
                          ntp_configuration='system_default' if state.ntp_server == 'null' else 'custom')
            check = clock_check(fixer, state.ntp_server)
            report.update(clock_status=check.status_at(), clock_difference_seconds=check.difference_seconds,
                          clock_uncertainty_seconds=check.uncertainty_seconds)
        except DeviceStateError as error:
            report['read_failure'] = error.code
        except Exception:
            report['read_failure'] = 'unavailable'
    return report


def advanced_time_action(fixer, action):
    try:
        if action in ('s', 'b'):
            _snapshot(fixer, restore=action == 'b')
        elif action == 'm':
            _set_multiple(fixer)
        elif action == 'p':
            _profiles(fixer)
        elif action == 'd':
            path = input(locales.get('state_diagnostic_path')).strip()
            if not path:
                return
            fixer._atomic_write_json(Path(path).expanduser(), diagnostic_report(fixer))
            print(locales.get('state_diagnostic_saved'))
        elif action == 'v':
            device = require_device(fixer)
            raw = checked_shell(device, 'settings get global ntp_server')
            references = _references(fixer, raw)
            if not references:
                raise DeviceStateError('state_clock_no_server')
            print(locales.get('state_monitor_start'))
            try:
                summary = monitor_device_time(lambda command, timeout: fixer._timed_device_shell(device, command, timeout),
                                              references, print_clock_check)
                print(locales.get('state_monitor_summary', samples=summary.samples, skipped=summary.skipped))
            except KeyboardInterrupt:
                print(locales.get('state_monitor_stopped'))
    except DeviceStateError as error:
        print(Fore.RED + locales.get(error.code))
        if error.rollback is not None:
            print(locales.get('state_compensation_ok' if error.rollback else 'state_compensation_failed'))
    except Exception:
        print(Fore.RED + locales.get('state_local_failed'))
