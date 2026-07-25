#!/usr/bin/env python3
"""Trigger router-control preload from WSS ready, with one quiet HTTP fallback."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterRepository.kt"
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


def apply() -> None:
    repo = REPOSITORY.read_text(encoding="utf-8")
    old_fields = '''    private val preloadStarted = AtomicBoolean(false)
    private val preloadMutex = Mutex()'''
    new_fields = '''    private val preloadStarted = AtomicBoolean(false)
    private val fallbackScheduled = AtomicBoolean(false)
    private val lastReconnectRefreshAt = AtomicLong(0L)
    private val preloadMutex = Mutex()'''
    if new_fields not in repo:
        if old_fields not in repo:
            raise RuntimeError("missing repository preload fields")
        repo = repo.replace(old_fields, new_fields, 1)

    old_start = '''    fun start() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        if (!preloadStarted.compareAndSet(false, true)) return
        scope.launch {
            // Let the WSS connection and its immediate memory snapshot win startup.
            delay(700L)
            preload()
            // One quiet recovery pass for a cold Hub/router session. This is not a
            // visible reconnect loop and never clears an existing value.
            if (_status.value.value == null || _ddns.value.value == null) {
                delay(5_000L)
                preloadMissing()
            }
        }
    }
'''
    new_start = '''    fun start() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        if (!fallbackScheduled.compareAndSet(false, true)) return
        scope.launch {
            // WSS ready is the preferred trigger. This fallback covers Hub versions
            // that cannot deliver the ready event, without exposing a reconnect loop.
            delay(3_000L)
            beginInitialPreload()
        }
    }

    fun onRealtimeReady(reconnect: Boolean) {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        if (!preloadStarted.get()) {
            beginInitialPreload()
            return
        }
        if (!reconnect) return
        val now = System.currentTimeMillis()
        val previous = lastReconnectRefreshAt.get()
        if (now - previous < 15_000L || !lastReconnectRefreshAt.compareAndSet(previous, now)) return
        scope.launch {
            // Reconnection refreshes only lightweight/visible essentials. It never
            // fans out all slow settings and never touches the realtime WSS collector.
            preloadMutex.withLock {
                refreshStatus(force = true)
                refreshCapabilities()
                refreshDdns()
            }
        }
    }

    private fun beginInitialPreload() {
        if (!preloadStarted.compareAndSet(false, true)) return
        scope.launch {
            preload()
            if (_status.value.value == null || _ddns.value.value == null) {
                delay(5_000L)
                preloadMissing()
            }
        }
    }
'''
    if new_start not in repo:
        if old_start not in repo:
            raise RuntimeError("missing repository start function")
        repo = repo.replace(old_start, new_start, 1)
    REPOSITORY.write_text(repo, encoding="utf-8")

    main = MAIN.read_text(encoding="utf-8")
    old_callback = '''        onRealtimeReady = { _ ->
            // Hub sends ready and the latest memory snapshots immediately. Calibrate
            // every open in parallel so a first connection cannot miss terminal data.
            stateScope.launch { calibrateRealtimeCache() }
        }'''
    new_callback = '''        onRealtimeReady = { reconnect ->
            // WSS wins startup. Router settings preload starts only after Hub ready;
            // reconnect refresh is silent and limited to lightweight essentials.
            RouterRepositoryRegistry.get(prefs).onRealtimeReady(reconnect)
            stateScope.launch { calibrateRealtimeCache() }
        }'''
    if new_callback not in main:
        if old_callback not in main:
            raise RuntimeError("missing WSS ready callback")
        main = main.replace(old_callback, new_callback, 1)
    MAIN.write_text(main, encoding="utf-8")

    required = (
        'fun onRealtimeReady(reconnect: Boolean)',
        'delay(3_000L)',
        'now - previous < 15_000L',
        'RouterRepositoryRegistry.get(prefs).onRealtimeReady(reconnect)',
    )
    combined = repo + main
    missing = [needle for needle in required if needle not in combined]
    if missing:
        raise RuntimeError(f"WSS preload trigger verification failed: {missing}")
    print("build158 WSS-ready router preload and quiet reconnect refresh applied")


if __name__ == "__main__":
    apply()
