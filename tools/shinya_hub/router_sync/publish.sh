#!/bin/sh
# Firmware's old continuous publisher can hang; every attempt is time bounded.
export XDG_CONFIG_HOME=/etc/shinya-agent/client
state=/tmp/shinya-agent
mkdir -p "$state"
chmod 700 "$state"
latest="$state/latest.json"
sending="$state/sending-$$.json"
cleanup() {
    trap - EXIT TERM INT
    [ -n "$collector" ] && kill "$collector" 2>/dev/null
    [ -n "$publisher" ] && kill "$publisher" 2>/dev/null
    rm -f "$sending"
}
trap 'cleanup; exit 0' TERM INT
trap cleanup EXIT
lua /etc/shinya-agent/collector.lua /etc/shinya-agent/config.json &
collector=$!
sn=$(cat /etc/shinya-agent/sn)
while kill -0 "$collector" 2>/dev/null; do
    if mv "$latest" "$sending" 2>/dev/null; then
        # QoS 1 acknowledgement or a hard timeout. New snapshots never block.
        timeout -t 8 -s 9 mosquitto_pub -i "shinya-up-$sn" \
            -t "shinya/v1/$sn/up" -q 1 -k 15 -f "$sending" &
        publisher=$!
        wait "$publisher"
        publisher=
        rm -f "$sending"
    fi
    sleep 1
done
exit 1
