"""A DER SEQUENCE of integers, as pycryptodome's DerSequence (encode only)."""


def _der_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(raw)]) + raw


def _der_int(value: int) -> bytes:
    raw = value.to_bytes(max(1, (value.bit_length() + 8) // 8), "big", signed=False)
    if raw[0] & 0x80:
        raw = b"\x00" + raw
    while len(raw) > 1 and raw[0] == 0 and not raw[1] & 0x80:
        raw = raw[1:]
    return b"\x02" + _der_len(len(raw)) + raw


class DerSequence(list):
    def encode(self) -> bytes:
        body = b"".join(_der_int(int(v)) for v in self)
        return b"\x30" + _der_len(len(body)) + body
