import socket
import subprocess
from pathlib import Path

# ── Network ──────────────────────────────────────────────────────────────────
PORT = 8765
HOST = "0.0.0.0"  # listen on all network interfaces


def local_ip() -> str:
    """
    This Mac's LAN IPv4 address.

    We ask the physical interfaces directly (en0 = Wi-Fi, en1/en2 = Ethernet/USB)
    via `ipconfig getifaddr`. This deliberately avoids the old "connect a UDP socket
    to 8.8.8.8 and read the source IP" trick, which returns the *VPN/proxy tunnel*
    address (e.g. 198.18.x via utun) when one is active — an address the phone on the
    same Wi-Fi cannot reach. Falls back to the socket trick if no en* has an address.
    """
    for dev in ("en0", "en1", "en2"):
        try:
            ip = subprocess.run(
                ["ipconfig", "getifaddr", dev],
                capture_output=True, text=True, timeout=2,
            ).stdout.strip()
            if ip:
                return ip
        except Exception:
            pass

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

# Shared secret sent by Android in the x-token header.
# Change this to something private before first use.
TOKEN = "changeme"

# ── Storage ───────────────────────────────────────────────────────────────────
SAVE_DIR = Path.home() / "Downloads" / "AndroidDrop"
SAVE_DIR.mkdir(parents=True, exist_ok=True)

# ── App identity ──────────────────────────────────────────────────────────────
APP_NAME = "AndroidDrop"
VERSION = "0.1.0"
