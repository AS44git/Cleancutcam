"""Present so imports work; not used by the cloud live stream."""


def import_key(*_a, **_k):
    raise NotImplementedError("RSA is not available in CleancutCams")


importKey = import_key


def construct(*_a, **_k):
    raise NotImplementedError("RSA is not available in CleancutCams")
