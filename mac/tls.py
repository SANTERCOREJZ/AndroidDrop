"""
Self-signed TLS for the local HTTPS/WSS server.

We generate one keypair + self-signed certificate the first time the app runs and
reuse it forever (stored in ~/Library/Application Support/AndroidDrop). The phone
pins this certificate's public key on first connect (trust-on-first-use), so all
traffic — the token and the clipboard contents — is encrypted, and a swapped key
(a man-in-the-middle) is rejected.

We pin the PUBLIC KEY (its SubjectPublicKeyInfo), not the hostname, so the Mac's IP
can change freely without breaking the pin. The pin format (base64 SHA-256 of the
DER SubjectPublicKeyInfo) is exactly what OkHttp / the Android side compute.
"""

import base64
import datetime
import hashlib
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

import config

_DIR = Path.home() / "Library" / "Application Support" / "AndroidDrop"
CERT_PATH = _DIR / "cert.pem"
KEY_PATH = _DIR / "key.pem"


def ensure_cert() -> tuple[str, str]:
    """Generate the cert + key on first run; reuse them afterwards. Returns paths."""
    if CERT_PATH.exists() and KEY_PATH.exists():
        return str(CERT_PATH), str(KEY_PATH)

    _DIR.mkdir(parents=True, exist_ok=True)

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, config.APP_NAME)])
    now = datetime.datetime.utcnow()
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))   # ~10 years
        .add_extension(
            x509.SubjectAlternativeName([x509.DNSName(config.APP_NAME)]),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )

    KEY_PATH.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    CERT_PATH.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    KEY_PATH.chmod(0o600)
    return str(CERT_PATH), str(KEY_PATH)


def spki_pin() -> str:
    """Base64 SHA-256 of the cert's SubjectPublicKeyInfo (matches OkHttp/Android)."""
    ensure_cert()
    cert = x509.load_pem_x509_certificate(CERT_PATH.read_bytes())
    spki = cert.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(hashlib.sha256(spki).digest()).decode()
