#!/usr/bin/env bash
# Sètifika HTTPS ki mache sou ansyen Android / Fire TV (RSA, chèn ISRG Root X1).
# Itilizasyon: curl -sLo f raw.githubusercontent.com/bentechtvstick-glitch/bentech-backend/main/deploy/fix-tls.sh ; bash f
set -euo pipefail
DOMAIN=panel.galaxytvstick.net
cat > /etc/caddy/Caddyfile <<CADDY
{
	key_type rsa2048
}
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:3000
}
CADDY
systemctl stop caddy
# Retire ansyen sètifika ECDSA a pou Caddy pran yon nouvo RSA
rm -rf /var/lib/caddy/.local/share/caddy/certificates
systemctl start caddy
echo "Ap tann nouvo sètifika a..."
for i in $(seq 1 30); do
  sleep 3
  out=$(echo | openssl s_client -connect 127.0.0.1:443 -servername $DOMAIN 2>/dev/null | openssl x509 -noout -text 2>/dev/null || true)
  if echo "$out" | grep -q "rsaEncryption"; then
    echo "$out" | grep -E "Issuer:|Public Key Algorithm" | head -3
    echo "OK: sètifika RSA a pare. Etenn/limen TV a."
    exit 0
  fi
done
echo "Sètifika a poko pare. Tape: journalctl -u caddy -n 30 --no-pager"
