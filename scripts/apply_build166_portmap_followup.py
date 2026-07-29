#!/usr/bin/env python3
"""Build166 follow-up: make stale mapping state neutral and action buttons truthful."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORTMAP = ROOT / "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build166 portmap follow-up anchor: {label}")
    return text.replace(old, new, 1)


text = PORTMAP.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''    val shouldStop: Boolean get() = effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || (syncState == "syncing" && effectiveDesiredState == "running")''',
    '''    val shouldStop: Boolean get() = effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || (syncState in setOf("syncing", "stale") && effectiveDesiredState == "running")''',
    "stale desired-state action",
)

text = replace_once(
    text,
    '''                rules = rules.map { it.copy(syncState = "stale") }''',
    '''                val staleRules = rules.map { it.copy(syncState = "stale") }
                rules = staleRules
                PortMappingMemoryCache.rules = staleRules''',
    "stale memory snapshot",
)

text = replace_once(
    text,
    '''            "运行中" -> it.effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || it.syncState == "syncing"''',
    '''            "运行中" -> it.effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || it.syncState == "syncing" || (it.syncState == "stale" && it.effectiveDesiredState == "running")''',
    "running filter includes stale desired running",
)

old_banner = '''        AnimatedVisibility(message.isNotBlank()) {
            Surface(shape = RoundedCornerShape(18.dp), color = PortRed.copy(alpha = .09f), border = androidx.compose.foundation.BorderStroke(1.dp, PortRed.copy(alpha = .16f))) {
                Text(message, Modifier.padding(12.dp), color = PortRed, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }'''
new_banner = '''        AnimatedVisibility(message.isNotBlank()) {
            val informational = message.startsWith("Agent 在线") || message.contains("已保留")
            val messageColor = if (informational) Color(0xFFF59E0B) else PortRed
            Surface(shape = RoundedCornerShape(18.dp), color = messageColor.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, messageColor.copy(alpha = .15f))) {
                Text(message, Modifier.padding(12.dp), color = messageColor, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }'''
text = replace_once(text, old_banner, new_banner, "neutral stale-state banner")

PORTMAP.write_text(text, encoding="utf-8")

required = (
    'syncState in setOf("syncing", "stale")',
    'PortMappingMemoryCache.rules = staleRules',
    'it.syncState == "stale" && it.effectiveDesiredState == "running"',
    'val messageColor = if (informational) Color(0xFFF59E0B) else PortRed',
)
missing = [item for item in required if item not in text]
if missing:
    raise RuntimeError(f"build166 portmap follow-up verification failed: {missing}")
print("build166 portmap stale-state presentation finalized")
