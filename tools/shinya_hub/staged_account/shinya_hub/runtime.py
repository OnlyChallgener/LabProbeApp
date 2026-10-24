"""Standalone service bootstrap; no dependency on the old Hub process."""
import atexit
import os
import threading
from pathlib import Path

from .api import Principal, create_app
from .assets import PublicIconStore
from .catalog import AppCatalog
from .commands import UFCommand
from .devices import DeviceDriver, DeviceKey, DeviceRegistry
from .store import ProjectStore
from .vault import ConfigVault
from .telemetry import USER_LIST, WAN_FLOW
from .auth import AccountStore


def build_runtime(config_directory: Path, data_directory: Path):
    os.umask(0o077)
    settings = ConfigVault(config_directory).load()
    store = ProjectStore(data_directory / "shinya.sqlite")
    registry = DeviceRegistry(store, workers=6, max_pending=64)
    keys = []
    for project in settings["projects"]:
        store.add_project(project["tenant"], project["id"], project["name"])
    for device in settings["devices"]:
        key = DeviceKey(device["tenant"], device["project"], device["sn"])
        store.bind_device(key.tenant, key.project, key.sn, device["metadata"])
        registry.register(key, DeviceDriver(device["address"], device["password"],
                                            device["family"], expected_sn=key.sn))
        keys.append(key)
    principals = {digest: Principal(**entry) for digest, entry in settings["principals"].items()}
    accounts = AccountStore(data_directory / "accounts.sqlite")
    app = create_app(store, registry, principals,
                     enabled_writes={tuple(v) for v in settings.get("enabledWrites", [])},
                     icons=PublicIconStore(data_directory / "icons"), catalog=AppCatalog(),
                     accounts=accounts, allow_legacy_tokens=settings.get("allowLegacyTokens", True))
    stopping = threading.Event()

    def collect():
        # Rotate admission order so a full bounded queue cannot starve later
        # devices. All work remains per-device; no HTTP request waits for this.
        order = list(keys)
        while not stopping.is_set():
            for key in order:
                registry.cached_read(key, UFCommand("dev_sta", "get", "sysinfo", None), ttl=30)
            for key in order:
                registry.cached_read(key, USER_LIST, ttl=30)
                registry.cached_read(key, WAN_FLOW, ttl=30)
                registry.cached_read(key, UFCommand("dev_sta","get","overview",None), ttl=30)
                registry.cached_read(key, UFCommand("dev_sta","get","radioInfo",None), ttl=60)
                registry.cached_read(key, UFCommand("dev_cap", "get", "gateway", None), ttl=3600)
                registry.cached_read(key, UFCommand("dev_cap", "get", "eweb", None), ttl=3600)
                registry.cached_read(key, UFCommand("ac_config", "get", "wireless", None), ttl=30)
                registry.cached_read(key, UFCommand("ac_config", "get", "timeReboot", None), ttl=30)
                registry.cached_read(key, UFCommand("dev_sta", "get", "ap_list", None), ttl=120)
            if order:
                order = order[1:] + order[:1]
            # Retry admission after the bounded queue drains. Fresh data never
            # re-enters the queue, so devices beyond its capacity get a turn.
            stopping.wait(5)

    threading.Thread(target=collect, name="shinya-background", daemon=True).start()

    def close():
        stopping.set()
        registry.close()

    atexit.register(close)
    app.extensions["shinya_registry"] = registry
    app.extensions["shinya_store"] = store
    app.extensions["shinya_accounts"] = accounts
    return app
