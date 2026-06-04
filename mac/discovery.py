"""
mDNS / Bonjour advertising — lets Android find this Mac automatically.

We publish a service of type `_androiddrop._tcp` on the local network. Android's
built-in NsdManager browses for it and resolves the *current* IP + port, so the
Mac's IP can change (new Wi-Fi, DHCP renewal, reboot) without you ever editing
anything on the phone.

A small background thread re-checks the IP every 30s and updates the advert if it
changed, so discovery keeps working even while the app stays open across a network
switch.

Uses the `zeroconf` pip package — a pure-Python mDNS implementation.
"""

import atexit
import socket
import threading
import time

from zeroconf import ServiceInfo, Zeroconf

import config

# Standard mDNS service type. The trailing "._tcp.local." suffix is required.
SERVICE_TYPE = "_androiddrop._tcp.local."
_REFRESH_SECONDS = 30

_zeroconf: Zeroconf | None = None
_current_ip: str | None = None


def _make_info(ip: str) -> ServiceInfo:
    """Build the advert record for the given IP."""
    hostname = socket.gethostname().split(".")[0]
    return ServiceInfo(
        type_=SERVICE_TYPE,
        # Instance name shown to clients; must be unique-ish and end with the type.
        name=f"{config.APP_NAME} on {hostname}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=config.PORT,
        properties={"version": config.VERSION},
        server=f"{hostname}.local.",
    )


def start() -> None:
    """Register the AndroidDrop service and start watching for IP changes."""
    global _zeroconf, _current_ip
    if _zeroconf is not None:
        return  # already running

    try:
        _zeroconf = Zeroconf()
        _current_ip = config.local_ip()
        # allow_name_change keeps us from crashing if the name is already taken
        # (e.g. a second instance) — zeroconf just appends a numeric suffix.
        _zeroconf.register_service(_make_info(_current_ip), allow_name_change=True)
        atexit.register(stop)
        threading.Thread(target=_watch, daemon=True).start()
    except Exception as e:
        # Auto-discovery is a convenience, not core — never let it take down the app.
        # The phone can still connect via a manually entered IP.
        print(f"[discovery] mDNS advertise failed, continuing without it: {e}")
        stop()


def _watch() -> None:
    """Re-advertise with the new address whenever the Mac's IP changes."""
    global _current_ip
    while _zeroconf is not None:
        time.sleep(_REFRESH_SECONDS)
        ip = config.local_ip()
        if ip != _current_ip and _zeroconf is not None:
            try:
                _zeroconf.update_service(_make_info(ip))
            except Exception:
                pass
            _current_ip = ip


def stop() -> None:
    """Unregister and close — called on quit / interpreter exit."""
    global _zeroconf
    z, _zeroconf = _zeroconf, None  # signal the watch thread to exit
    if z is not None:
        try:
            z.unregister_all_services()
        except Exception:
            pass
        z.close()
