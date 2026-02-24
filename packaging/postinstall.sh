#!/bin/sh
set -e

# NFPM Unified Post-Install Script (DEB/RPM)
# DEB actions: "configure", "abort-upgrade", "abort-remove", "abort-deconfigure"
# RPM actions: 1 (install), 2 (upgrade)

ACTION="$1"

setup_user_and_permissions() {
    # Create non-root system user 'yuubin'
    if ! getent passwd yuubin >/dev/null; then
        useradd -r -M -s /usr/sbin/nologin -d /var/empty yuubin
    fi

    # Ensure secure directory permissions
    install -d -m 0755 -o yuubin -g yuubin /var/log/yuubin-proxy
    install -d -m 0755 -o yuubin -g yuubin /etc/yuubin-proxy
}

setup_systemd() {
    if [ -x "$(command -v systemctl)" ]; then
        systemctl daemon-reload
        
        # Fresh Install (DEB configure, RPM 1)
        if [ "$ACTION" = "configure" ] || [ "$ACTION" = "1" ]; then
            systemctl enable yuubin-proxy.service
            systemctl start yuubin-proxy.service || true
            
        # Upgrade (DEB configure, RPM 2)
        elif [ "$ACTION" = "2" ]; then
            # DEB upgrades also use "configure" but the systemd service usually remains active.
            # RPM explicitly uses 2. 
            if systemctl is-active --quiet yuubin-proxy.service; then
                systemctl restart yuubin-proxy.service || true
            fi
        fi
    fi
}

if [ "$ACTION" = "configure" ] || [ "$ACTION" = "1" ] || [ "$ACTION" = "2" ]; then
    setup_user_and_permissions
    setup_systemd
fi
