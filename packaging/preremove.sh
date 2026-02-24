#!/bin/sh
set -e

# NFPM Unified Pre-Remove Script (DEB/RPM)
# DEB actions: "remove", "upgrade", "deconfigure", "failed-upgrade"
# RPM actions: 0 (remove), 1 (upgrade)

ACTION="$1"

# We only stop and disable the service on a full REMOVE. 
# We do NOT stop the service on an upgrade.
if [ "$ACTION" = "remove" ] || [ "$ACTION" = "0" ]; then
    if [ -x "$(command -v systemctl)" ]; then
        systemctl stop yuubin-proxy.service || true
        systemctl disable yuubin-proxy.service || true
    fi
fi
