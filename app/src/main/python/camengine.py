"""
camengine - the Hik-Connect side of CleancutCams, run by the app through Chaquopy.

The same code the laptop camera wall uses (hikcloudstream + pyezvizapi, with hikfix's
fixes), without the desktop video decoder: it hands the app clean H.264/H.265 Annex B
(start code + NAL units, decrypted), and the phone's own video decoder does the rest.

Called from Kotlin:
    login(user, password) -> JSON list of cameras
    Stream(number, stream_type, code)   .read() -> bytes (complete NAL units), .close()
"""

from __future__ import annotations

import json
import struct
import threading
import time
from typing import Any

from hikcloudstream import Credentials, HikConnectClient
from hikcloudstream.capture import aes_key_material
from hikcloudstream.stream.adapter import HikConnectStreamAdapter
from hikcloudstream.stream.rtp import rtp_h264_to_annexb
from hikcloudstream.stream.session import open_live_stream
from pyezvizapi.api_endpoints import API_ENDPOINT_PAGELIST
from pyezvizapi.exceptions import PyEzvizError
from pyezvizapi.stream import (
    StreamTransport,
    VtmChannel,
    _video_pes_payload_ranges,
    detect_transport,
    mpeg_ps_complete_prefix_length,
)
from Crypto.Cipher import AES

# ---------------------------------------------------------------------------
# hikfix fix 1: follow every page of the VTM relay list (accounts with more than
# 50 channels otherwise miss recorders: "could not find VTM resource").
# ---------------------------------------------------------------------------
PAGE_SIZE = 50
MAX_PAGES = 40


def _fetch_page(adapter, page_filter: str, group_id: int, limit: int, offset: int) -> dict:
    payload = adapter._request_json(
        "GET",
        API_ENDPOINT_PAGELIST,
        params={"groupId": group_id, "limit": limit, "offset": offset, "filter": page_filter},
    )
    meta = payload.get("meta") or {}
    if meta.get("code") != 200:
        raise PyEzvizError(f"pagelist error: {payload}")
    return payload


def _paged_api_get_pagelist(self, page_filter: str, json_key: str | None = None,
                            group_id: int = -1, limit: int = 30, offset: int = 0,
                            max_retries: int = 0) -> Any:
    del max_retries
    limit = max(int(limit or PAGE_SIZE), 1)
    merged: dict | None = None
    seen: set[str] = set()
    for _ in range(MAX_PAGES):
        page = _fetch_page(self, page_filter, group_id, limit, offset)
        if merged is None:
            merged = dict(page)
            merged["resourceInfos"] = []
            merged["deviceInfos"] = []
        new_items = 0
        for item in page.get("resourceInfos") or []:
            key = str(item.get("resourceId")) if isinstance(item, dict) else repr(item)
            if key not in seen:
                seen.add(key)
                merged["resourceInfos"].append(item)
                new_items += 1
        for item in page.get("deviceInfos") or []:
            merged["deviceInfos"].append(item)
            new_items += 1
        for key, value in page.items():
            if key in ("resourceInfos", "deviceInfos", "meta", "page"):
                continue
            if isinstance(value, dict):
                existing = merged.get(key)
                if isinstance(existing, dict) and existing is not value:
                    existing.update(value)
                else:
                    merged[key] = dict(value)
        info = page.get("page") or {}
        has_next = info.get("hasNext") if isinstance(info, dict) else None
        returned = len(page.get("resourceInfos") or page.get("deviceInfos") or [])
        if has_next is False or new_items == 0 or returned == 0:
            break
        offset += returned
    merged = merged or {}
    return merged.get(json_key) if json_key else merged


HikConnectStreamAdapter._api_get_pagelist = _paged_api_get_pagelist

# ---------------------------------------------------------------------------
# The account
# ---------------------------------------------------------------------------
_lock = threading.RLock()
_client: HikConnectClient | None = None
_cameras: list = []
_credentials: tuple[str, str] | None = None


def login(user: str, password: str) -> str:
    """Logs in and returns the account's cameras as JSON."""
    global _client, _cameras, _credentials
    with _lock:
        if _client is not None:
            try:
                _client.close()
            except Exception:  # noqa: BLE001
                pass
        client = HikConnectClient()
        client.login(Credentials(user, password))
        cameras = client.list_cameras()
        _client, _cameras, _credentials = client, cameras, (user, password)
        return json.dumps([
            {"n": i + 1, "name": c.name.split(" (ch")[0], "recorder": c.device_name,
             "serial": c.device_serial, "channel": c.channel_no}
            for i, c in enumerate(cameras)
        ])


def relogin() -> None:
    """A fresh session with the saved credentials (sessions expire after a while)."""
    if _credentials is None:
        raise RuntimeError("not logged in")
    login(*_credentials)


# ---------------------------------------------------------------------------
# hikfix fix 2: live decryption. The scheme on these recorders (DS-7108HGHI-F1 and
# alike): start code + a clear copy of the NAL header (1 byte H.264, 2 bytes H.265) +
# AES-128 of the whole NAL (header included) over its first 4096 bytes in whole blocks,
# zero IV per block (so: ECB) + the rest in the clear. Self-checking: a decrypted NAL
# must begin with the header bytes it was sent with. Not every NAL is encrypted, so
# each one is checked on its own.
# ---------------------------------------------------------------------------
def _decrypt_nal(nal: bytes, cipher) -> bytes | None:
    """The NAL decrypted, or None when it doesn't look encrypted with this key."""
    for h in (1, 2):
        if len(nal) < h + 16:
            continue
        body = nal[h:]
        first = cipher.decrypt(body[:16])
        if first[:h] != nal[:h]:
            continue
        n = min(len(body), 4096) // 16 * 16
        return cipher.decrypt(body[:n]) + body[n:]
    return None


def _start_codes(data) -> list[tuple[int, int]]:
    """Annex B start codes in data as (position, length), like the library's scanner, but
    with bytes.find (C speed): the byte-by-byte one cost most of the phone's CPU."""
    out: list[tuple[int, int]] = []
    i = data.find(b"\x00\x00\x01")
    while i >= 0:
        if i > 0 and data[i - 1] == 0 and (not out or out[-1][0] + out[-1][1] <= i - 1):
            out.append((i - 1, 4))
        else:
            out.append((i, 3))
        i = data.find(b"\x00\x00\x01", i + 3)
    return out


class Stream:
    """One live stream: complete, decrypted NAL units in Annex B form."""

    def __init__(self, number: int, stream_type: int, code: str | None) -> None:
        if _client is None:
            raise RuntimeError("not logged in")
        self.camera = _cameras[number - 1]
        self.stream_type = stream_type
        self.cipher = AES.new(aes_key_material(code), AES.MODE_ECB) if code else None
        self.mode: str | None = None if code else "plain"   # "decrypt" / "plain" once known
        self.checked = 0
        self.matched = 0
        self.transport = StreamTransport.UNKNOWN
        self.es = bytearray()      # elementary stream not yet cut into whole NALs
        self.ps = bytearray()      # MPEG-PS bytes not yet parsed into whole packets
        self.closed = False
        # How far behind live the stream is, from the RTP clock (90 kHz) against ours.
        self.video_pt: int | None = None
        self.last_ts: int | None = None
        self.media_s = 0.0
        self.best_offset: float | None = None
        self.behind = 0.0
        with _lock:
            self.session = open_live_stream(_client, self.camera, stream_type=stream_type)
            self.session.__enter__()
            self.session.start()
        self.packets = self.session._client.iter_packets()

    def close(self) -> None:
        self.closed = True
        try:
            self.session.close()
        except Exception:  # noqa: BLE001
            pass

    # ------------------------------------------------------------------
    def read(self) -> bytes:
        """Blocks until at least one whole NAL unit is ready; returns them (Annex B)."""
        while not self.closed:
            packet = next(self.packets)
            if packet.channel not in (VtmChannel.STREAM, VtmChannel.ENCRYPTED_STREAM):
                continue
            body = packet.body
            if not body:
                continue
            detected = detect_transport(body)
            if self.transport == StreamTransport.UNKNOWN and detected != StreamTransport.UNKNOWN:
                self.transport = detected
            transport = detected if detected != StreamTransport.UNKNOWN else self.transport
            if transport == StreamTransport.RTP:
                chunk = rtp_h264_to_annexb(body)
                if chunk:
                    self._clock(body)
            elif transport == StreamTransport.MPEG_PS:
                chunk = self._from_ps(body)
            else:
                chunk = body
            if not chunk:
                continue
            self.es += chunk
            ready = self._whole_nals()
            if ready:
                return self._decrypt(ready)
        return b""

    def _from_ps(self, body: bytes) -> bytes:
        """The video payload of every complete MPEG-PS packet received so far."""
        self.ps += body
        complete = mpeg_ps_complete_prefix_length(self.ps)
        if complete <= 0:
            if len(self.ps) > 8 * 1024 * 1024:   # never complete: drop it
                self.ps.clear()
            return b""
        data = bytes(self.ps[:complete])
        del self.ps[:complete]
        return b"".join(data[a:b] for a, b in _video_pes_payload_ranges(data))

    def _clock(self, rtp: bytes) -> None:
        pt = rtp[1] & 0x7F
        if self.video_pt is None:
            self.video_pt = pt
        if pt != self.video_pt or len(rtp) < 12:
            return
        ts = struct.unpack_from(">I", rtp, 4)[0]
        if self.last_ts is not None:
            step = ((ts - self.last_ts + 0x80000000) & 0xFFFFFFFF) - 0x80000000
            if -90000 * 5 < step < 90000 * 5:   # ignore jumps (camera clock reset)
                self.media_s += step / 90000.0
        self.last_ts = ts
        offset = time.monotonic() - self.media_s
        # The smallest offset seen is when the stream was most up to date.
        if self.best_offset is None or offset < self.best_offset:
            self.best_offset = offset
        self.behind = offset - self.best_offset

    def lag(self) -> float:
        """Seconds the stream is behind live (0 when keeping up)."""
        return self.behind

    def reset_lag(self) -> None:
        """Measure from now on (a camera clock running a little slow looks like lag)."""
        self.best_offset = None
        self.behind = 0.0

    def _whole_nals(self) -> bytes:
        """Cuts the buffer before its last start code: everything before it is whole NALs."""
        es = self.es
        first = es.find(b"\x00\x00\x01")
        last = es.rfind(b"\x00\x00\x01")
        if first < 0 or last <= first:
            if len(es) > 4 * 1024 * 1024:
                es.clear()
            return b""
        # A 4-byte start code (00 00 00 01) begins one byte earlier.
        cut = last - 1 if last - 1 > first + 2 and es[last - 1] == 0 else last
        ready = bytes(es[:cut])
        del self.es[:cut]
        return ready

    def _decrypt(self, data: bytes) -> bytes:
        if self.mode == "plain" or self.cipher is None:
            return data
        starts = _start_codes(data)
        if not starts:
            return data
        out = bytearray(data[:starts[0][0]])
        for i, (pos, length) in enumerate(starts):
            end = starts[i + 1][0] if i + 1 < len(starts) else len(data)
            nal = data[pos + length:end]
            plain = _decrypt_nal(nal, self.cipher) if len(nal) >= 17 else None
            if self.mode is None and len(nal) >= 17:
                self.checked += 1
                self.matched += plain is not None
                if self.checked >= 64:
                    # These recorders encrypt only some NAL units (parameter sets, key
                    # frames, about a quarter of the rest), so an encrypted stream matches
                    # a third or more. A clear one matches only by chance (1 in 256).
                    self.mode = "decrypt" if self.matched * 10 >= self.checked else "plain"
            out += b"\x00\x00\x00\x01"
            out += plain if (plain is not None and self.mode != "plain") else nal
        return bytes(out)
