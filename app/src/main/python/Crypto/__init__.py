"""
A small stand-in for pycryptodome's Crypto package, on Android's own javax.crypto.

The camera libraries only use AES (ECB and CBC, no padding) on the live-stream path;
pycryptodome is a C extension with no build for Android's Python, so this provides the
same names backed by the platform. RSA/PKCS#1 (used only by pyezvizapi's local-network
SDK, never by the cloud stream) is present so imports work, and refuses if called.
"""
