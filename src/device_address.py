"""Числовые IPv4/IPv6 адреса ADB; порт IPv6 задаётся только после скобок."""
import ipaddress
import re


def parse_address(value, default_port=5555):
    if not isinstance(value, str):
        raise ValueError('Invalid address')
    value = value.strip()
    host, port = value, default_port
    port_text = None
    bracketed = value.startswith('[')
    if bracketed:
        match = re.fullmatch(r'\[([^\[\]]+)\](?::([^:]*))?', value)
        if match is None:
            raise ValueError('Invalid IPv6 endpoint')
        host, port_text = match.groups()
    elif value.count(':') == 1:
        host, port_text = value.split(':')
    if port_text is not None:
        if not re.fullmatch(r'[0-9]{1,5}', port_text) or not 1 <= int(port_text) <= 65535:
            raise ValueError('Invalid port')
        port = int(port_text)
    if '%' in host:
        address, scope = host.split('%', 1)
        if ':' not in address or not re.fullmatch(r'[A-Za-z0-9_.-]{1,32}', scope):
            raise ValueError('Invalid IPv6 scope')
    parsed = ipaddress.ip_address(host)
    if bracketed and parsed.version != 6:
        raise ValueError('Brackets require IPv6')
    return host, port


def format_address(host, port):
    return f'[{host}]:{port}' if ':' in host else f'{host}:{port}'


def has_explicit_port(value):
    value = value.strip()
    return ']:' in value if value.startswith('[') else value.count(':') == 1
