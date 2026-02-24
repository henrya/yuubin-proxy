#!/bin/sh
set -e

# NFPM Unified Post-Remove Script (DEB/RPM)
# DEB actions: "remove", "purge", "upgrade", "failed-upgrade", "abort-install", "abort-upgrade", "disappear"
# RPM actions: 0 (remove), 1 (upgrade)

ACTION="$1"

# Reload system daemon map after script removal
if [ "$ACTION" = "remove" ] || [ "$ACTION" = "purge" ] || [ "$ACTION" = "0" ]; then
    if [ -x "$(command -v systemctl)" ]; then
        systemctl daemon-reload || true
        systemctl reset-failed yuubin-proxy.service || true
    fi
fi

# Deep scrub on Deb purge only. (RPM doesn't have an equivalent isolated purge).
if [ "$ACTION" = "purge" ]; then
    rm -rf /var/log/yuubin-proxy
    rm -rf /etc/yuubin-proxy
fi
