#!/bin/sh
# Decides which certificate nginx serves, before nginx starts and while it runs.
#   - /etc/letsencrypt (written by the certbot container) has a certificate for $DOMAIN: use it.
#   - otherwise: a self-signed one, so nginx can start and answer the Let's Encrypt challenge at all.
# The active pair lives in /etc/nginx/certs (a volume, which Mosquitto can share for MQTT over TLS).
set -e
LIVE="/etc/letsencrypt/live/$DOMAIN"
CERTS=/etc/nginx/certs
mkdir -p "$CERTS"

install_letsencrypt() {
    cp -L "$LIVE/fullchain.pem" "$CERTS/fullchain.pem.new"
    cp -L "$LIVE/privkey.pem" "$CERTS/privkey.pem.new"
    mv "$CERTS/fullchain.pem.new" "$CERTS/fullchain.pem"
    mv "$CERTS/privkey.pem.new" "$CERTS/privkey.pem"
    # for Mosquitto, which drops root before it reads its key; the volume is not reachable from outside Docker
    chmod 644 "$CERTS/privkey.pem"
}

if [ -f "$LIVE/fullchain.pem" ]; then
    install_letsencrypt
    echo "15-certificate.sh: using the Let's Encrypt certificate for $DOMAIN"
elif [ ! -f "$CERTS/fullchain.pem" ]; then
    openssl req -x509 -nodes -newkey rsa:2048 -days 90 -subj "/CN=$DOMAIN" -addext "subjectAltName=DNS:$DOMAIN" \
        -keyout "$CERTS/privkey.pem" -out "$CERTS/fullchain.pem" 2>/dev/null
    chmod 644 "$CERTS/privkey.pem"
    echo "15-certificate.sh: no certificate yet for $DOMAIN, serving a self-signed one"
fi

# Pick up a first or renewed Let's Encrypt certificate within a minute, without restarting nginx.
(
    while sleep 60; do
        if [ -f "$LIVE/fullchain.pem" ] && [ "$LIVE/fullchain.pem" -nt "$CERTS/fullchain.pem" ]; then
            install_letsencrypt
            nginx -s reload && echo "15-certificate.sh: loaded a new certificate for $DOMAIN"
        fi
    done
) &
