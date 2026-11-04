# Router display alias fix · 2026-11-04

Original Hermes 22794 calls setDeviceAlias with {apMac: router.mac, alias}, wrapped as data by 20740. No firmware hostname write is implied by this UI contract.

The missing homeAp/setDeviceAlias handler is implemented with existing owner/manager permissions, authoritative project selectors and exact projected router MAC matching. Conflicting serials, ambiguous/foreign MACs and absent targets fail closed. Display alias persists in the existing scoped snapshots store, survives startup metadata imports, is read back before success and is projected through getDeviceInfoList2. No firmware update, network rename or router hostname change.

Fresh live sources used as baseline. Only router_alias.py, projection.py and api.py deployed; exact pre-deploy hash checks. Backup: /data/router-alias-backup-1793786077. Health check passed.

Five isolated Flask route tests passed: original request + readback + reopened store; foreign project/account; conflicting selectors and readonly; invalid/missing values without main-router fallback; deleted router remains deleted. Production router display names were not changed merely for testing.

HubNotifySettings.java return chevron uses INK (black). Java compilation of 28 sources passed. Speed metrics already use one complete rounded strip, two fine vertical dividers, compact padding and wrapping non-ellipsized text in HubPages.java; build_internal.py compiles this source directly. Phone visual acceptance remains pending.
