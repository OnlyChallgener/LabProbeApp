package com.labprobe.app

/** Single source of truth for the Lucky update repository endpoints. */
object UpdateRepository {
    const val ROOT = "https://lab.net86.dynv6.net:27772"
    const val APP_MANIFEST = "$ROOT/app/update.json"
    const val AGENT_MANIFEST = "$ROOT/agent/latest.json"
    const val AGENT_INSTALLER = "$ROOT/agent/install.sh"
}
