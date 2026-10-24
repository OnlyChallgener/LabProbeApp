#!/bin/sh /etc/rc.common
START=97
STOP=10
USE_PROCD=1

start_service() {
    procd_open_instance
    procd_set_param command /bin/sh /etc/shinya-agent/publish.sh
    procd_set_param respawn 3600 5 0
    procd_set_param stdout 0
    procd_set_param stderr 1
    procd_close_instance
}

stop_service() {
    # Match complete owned command paths before killing any remaining child.
    # This also cleans children from the earlier pipeline-only launcher.
    for entry in /proc/[0-9]*/cmdline; do
        command=$(tr '\000' ' ' < "$entry" 2>/dev/null)
        case "$command" in
            'lua /etc/shinya-agent/collector.lua '*|'mosquitto_pub -l -i shinya-up-'*|'mosquitto_pub -i shinya-up-'*|'timeout -t 8 -s 9 mosquitto_pub -i shinya-up-'*)
                pid=${entry#/proc/}; pid=${pid%/cmdline}
                kill "$pid" 2>/dev/null
                ;;
        esac
    done
}
