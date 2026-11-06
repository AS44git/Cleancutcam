"""AES on javax.crypto, with pycryptodome's interface (new, MODE_*, encrypt/decrypt)."""

from __future__ import annotations

MODE_ECB = 1
MODE_CBC = 2
block_size = 16
key_size = (16, 24, 32)

try:  # On Android, through Chaquopy's Java bridge.
    from java import jclass

    _Cipher = jclass("javax.crypto.Cipher")
    _SecretKeySpec = jclass("javax.crypto.spec.SecretKeySpec")
    _IvParameterSpec = jclass("javax.crypto.spec.IvParameterSpec")
    _ENCRYPT = _Cipher.ENCRYPT_MODE
    _DECRYPT = _Cipher.DECRYPT_MODE

    def _make(key: bytes, mode: int, iv: bytes | None, direction: int):
        name = "AES/ECB/NoPadding" if mode == MODE_ECB else "AES/CBC/NoPadding"
        cipher = _Cipher.getInstance(name)
        spec = _SecretKeySpec(bytes(key), "AES")
        if mode == MODE_ECB:
            cipher.init(direction, spec)
        else:
            cipher.init(direction, spec, _IvParameterSpec(bytes(iv)))
        return cipher

    def _run(cipher, data: bytes) -> bytes:
        out = cipher.update(bytes(data))
        return bytes(out) if out is not None else b""

except ImportError:  # Off Android (tests): the same through the `cryptography` package.
    from cryptography.hazmat.primitives.ciphers import Cipher as _C, algorithms as _alg, modes as _modes

    _ENCRYPT, _DECRYPT = 1, 2

    def _make(key, mode, iv, direction):
        m = _modes.ECB() if mode == MODE_ECB else _modes.CBC(bytes(iv))
        c = _C(_alg.AES(bytes(key)), m)
        return c.encryptor() if direction == _ENCRYPT else c.decryptor()

    def _run(cipher, data):
        return cipher.update(bytes(data))


class _AESCipher:
    def __init__(self, key: bytes, mode: int, iv: bytes | None) -> None:
        if len(key) not in key_size:
            raise ValueError(f"Incorrect AES key length ({len(key)} bytes)")
        if mode not in (MODE_ECB, MODE_CBC):
            raise ValueError(f"unsupported AES mode {mode}")
        if mode == MODE_CBC:
            iv = bytes(iv) if iv is not None else bytes(16)
            if len(iv) != 16:
                raise ValueError("Incorrect IV length (it must be 16 bytes long)")
        self.key, self.mode, self.iv = bytes(key), mode, iv
        self._enc = None
        self._dec = None
        self.block_size = block_size

    def encrypt(self, data: bytes) -> bytes:
        if len(data) % 16:
            raise ValueError("Data must be aligned to block boundary in this mode")
        if self._dec is not None and self.mode == MODE_CBC:
            raise TypeError("encrypt() cannot be called after decrypt()")
        if self._enc is None:
            self._enc = _make(self.key, self.mode, self.iv, _ENCRYPT)
        return _run(self._enc, data)

    def decrypt(self, data: bytes) -> bytes:
        if len(data) % 16:
            raise ValueError("Data must be aligned to block boundary in this mode")
        if self._enc is not None and self.mode == MODE_CBC:
            raise TypeError("decrypt() cannot be called after encrypt()")
        if self._dec is None:
            self._dec = _make(self.key, self.mode, self.iv, _DECRYPT)
        return _run(self._dec, data)


def new(key: bytes, mode: int, iv: bytes | None = None, *args, **kwargs) -> _AESCipher:
    if iv is None:
        iv = kwargs.get("IV") or (args[0] if args else None)
    return _AESCipher(key, mode, iv)
