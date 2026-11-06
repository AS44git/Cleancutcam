"""PKCS#7 padding, as pycryptodome's Crypto.Util.Padding."""


def pad(data: bytes, block_size: int, style: str = "pkcs7") -> bytes:
    n = block_size - len(data) % block_size
    return bytes(data) + bytes([n]) * n


def unpad(data: bytes, block_size: int, style: str = "pkcs7") -> bytes:
    if not data or len(data) % block_size:
        raise ValueError("Input data is not padded")
    n = data[-1]
    if not 1 <= n <= block_size or data[-n:] != bytes([n]) * n:
        raise ValueError("Padding is incorrect.")
    return bytes(data[:-n])
