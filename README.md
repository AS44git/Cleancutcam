# CleancutCams

An Android camera wall for Hik-Connect: every camera on the account, in 7+1, 1+5, 2×2,
3×3 or 4×4 layouts. Tap a camera for full screen (pinch or double-tap to zoom, swipe for
the next), long-press to make it the big tile or remove it.

The Hik-Connect side is Python, run in the app by [Chaquopy](https://chaquo.com/chaquopy/):
[hikcloudstream](https://pypi.org/project/hikcloudstream/) and
[pyezvizapi](https://pypi.org/project/pyezvizapi/), with the fixes from the laptop camera
wall (relay paging, live decryption) in `app/src/main/python/camengine.py`. The phone's own
video decoder (MediaCodec) shows the picture.

Built by GitHub Actions (`.github/workflows/build.yml`); download the APK from the run's
artifacts and install with `adb install`.
