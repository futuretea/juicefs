"""Exercise the existing Python Client without installing either AgentFS package."""

import os

import juicefs
from juicefs import Client


def main():
    assert not hasattr(juicefs, "AgentFS")
    assert not hasattr(Client, "access")
    client = Client(os.environ["AGENTFS_LEGACY_VOLUME"], os.environ["AGENTFS_LEGACY_META"],
                    cache_dir="memory", no_usage_report=True)
    try:
        client.mkdir("/legacy-python-smoke")
        with client.open("/legacy-python-smoke/data", "wb") as stream:
            stream.write(b"unchanged-client")
        with client.open("/legacy-python-smoke/data", "rb") as stream:
            assert stream.read() == b"unchanged-client"
        assert client.stat("/legacy-python-smoke/data").st_size == len(b"unchanged-client")
        assert client.listdir("/legacy-python-smoke") == ["data"]
    finally:
        client.close(terminate=True)
    print("Legacy Python Client smoke passed")


if __name__ == "__main__":
    main()
