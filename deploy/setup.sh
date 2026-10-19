#!/usr/bin/env bash
# Galaxy panel: enstalasyon sou yon sèvè Ubuntu tou nèf (apre Rebuild), ak sekirite.
# Itilizasyon (nan konsòl Hetzner, kòm root):
#   curl -sLo s raw.githubusercontent.com/bentechtvstick-glitch/bentech-backend/main/deploy/setup.sh
#   bash s
# Li remete done yo soti nan Volume Hetzner nan (/dev/sdb → galaxy/ ak galaxy.env) si l la.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
REPO=https://github.com/bentechtvstick-glitch/bentech-backend.git
DOMAIN=panel.galaxytvstick.net
say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

say "1/8 Pakè sistèm yo (Node, Caddy, firewall, fail2ban, mizajou otomatik)"
apt-get update -q
apt-get install -y -q git curl ca-certificates ufw fail2ban unattended-upgrades nodejs npm caddy
node -v

say "2/8 SSH: sèlman ak kle (pa gen modpas)"
mkdir -p /etc/ssh/sshd_config.d
printf 'PasswordAuthentication no\nKbdInteractiveAuthentication no\nPermitRootLogin prohibit-password\n' > /etc/ssh/sshd_config.d/00-secure.conf
grep -q '^PasswordAuthentication no' /etc/ssh/sshd_config || sed -i '1i PasswordAuthentication no\nPermitRootLogin prohibit-password' /etc/ssh/sshd_config
systemctl restart ssh || systemctl restart sshd || true

say "3/8 Firewall + fail2ban + mizajou sekirite otomatik"
ufw allow 22/tcp >/dev/null; ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
ufw --force enable
systemctl enable --now fail2ban
dpkg-reconfigure -f noninteractive unattended-upgrades || true

say "4/8 Kòd panel la"
id galaxy >/dev/null 2>&1 || useradd --system --home /var/lib/galaxy --shell /usr/sbin/nologin galaxy
git config --global --add safe.directory /opt/galaxy
if [ -d /opt/galaxy/.git ]; then git -C /opt/galaxy fetch -q origin && git -C /opt/galaxy reset -q --hard origin/main
else rm -rf /opt/galaxy && git clone -q "$REPO" /opt/galaxy; fi
cd /opt/galaxy && npm install --omit=dev --no-audit --no-fund --ignore-scripts

say "5/8 Done yo (soti nan Volume nan)"
mkdir -p /var/lib/galaxy/data /mnt/sov
if ! mountpoint -q /mnt/sov && [ -b /dev/sdb ]; then mount /dev/sdb /mnt/sov || true; fi
if [ -f /mnt/sov/galaxy/data/db.json ]; then
  cp -a /mnt/sov/galaxy/. /var/lib/galaxy/
  rm -f /var/lib/galaxy/data/.jwt-secret   # nouvo kle sesyon: tout ansyen koneksyon panel yo fini
  echo "Done yo remete."
else
  echo "ATANSYON: pa jwenn /mnt/sov/galaxy/data/db.json. Panel la ap kòmanse vid."
fi
chown -R galaxy:galaxy /var/lib/galaxy
chmod 750 /var/lib/galaxy

say "6/8 Reglaj (/etc/galaxy.env) ak nouvo modpas"
NEWPASS=$(node -e 'process.stdout.write(require("crypto").randomBytes(15).toString("base64url").replace(/[-_]/g,"x"))')
{
  if [ -f /mnt/sov/galaxy.env ]; then
    # Kenbe reglaj yo, men JANM ansyen modpas la ni ansyen kle AI a (yo te ka vòlè yo)
    grep -v -E '^(ADMIN_PASS|ANTHROPIC_API_KEY|RESET_OWNER)=' /mnt/sov/galaxy.env || true
  else
    echo "ADMIN_USER=admin"; echo "PORT=3000"
  fi
  echo "ADMIN_PASS=$NEWPASS"
} > /etc/galaxy.env.new
grep -q '^DB_FILE=' /etc/galaxy.env.new || echo "DB_FILE=/var/lib/galaxy/data/db.json" >> /etc/galaxy.env.new
grep -q '^PORT=' /etc/galaxy.env.new || echo "PORT=3000" >> /etc/galaxy.env.new
mv /etc/galaxy.env.new /etc/galaxy.env
chmod 600 /etc/galaxy.env

say "7/8 Sèvis yo (panel, HTTPS, mizajou chak 3 minit)"
cat > /etc/systemd/system/galaxy.service <<UNIT
[Unit]
Description=Galaxy TV Stick panel
After=network-online.target
[Service]
EnvironmentFile=/etc/galaxy.env
WorkingDirectory=/opt/galaxy
ExecStart=$(command -v node) src/server.js
Restart=always
RestartSec=3
User=galaxy
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=/var/lib/galaxy
[Install]
WantedBy=multi-user.target
UNIT
cat > /etc/systemd/system/galaxy-update.service <<'UNIT'
[Unit]
Description=Galaxy panel auto-update from GitHub main
[Service]
Type=oneshot
WorkingDirectory=/opt/galaxy
ExecStart=/bin/sh -c 'git fetch -q origin && if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then git reset -q --hard origin/main && npm install --omit=dev --no-audit --no-fund --ignore-scripts && systemctl restart galaxy; fi'
UNIT
cat > /etc/systemd/system/galaxy-update.timer <<'UNIT'
[Unit]
Description=Check for panel updates every 3 minutes
[Timer]
OnBootSec=2min
OnUnitActiveSec=3min
[Install]
WantedBy=timers.target
UNIT
cat > /etc/caddy/Caddyfile <<CADDY
{
	key_type rsa2048
}
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:3000
}
CADDY
systemctl daemon-reload
if [ -f /var/lib/galaxy/data/db.json ]; then
  systemctl enable --now galaxy galaxy-update.timer
else
  # Pa janm limen yon panel vid: TV yo t ap kwè yo efase
  echo "PANEL LA PA LIMEN: pa gen done (db.json). Tcheke Volume nan, epi kouri script la ankò."
  systemctl enable galaxy-update.timer
fi
systemctl restart caddy

say "8/8 Verifikasyon"
sleep 4
systemctl is-active galaxy caddy fail2ban || true
curl -s -m 5 127.0.0.1:3000/api/health || echo "(panel la poko reponn)"
echo
# Kont admin ki nan done yo: tcheke pa gen yon kont ou pa konnen (piratè a te ka ajoute youn)
node -e 'try{const d=require("/var/lib/galaxy/data/db.json");console.log("Kont Admin Users:",(d.adminUsers||[]).map(a=>a.email+" ("+(a.role||"")+")").join(", ")||"okenn");console.log("TV:",(d.devices||[]).length,"· Kliyan:",(d.customers||[]).length)}catch(e){console.log("pa ka li db.json")}'
echo
echo "================================================================"
echo " FINI. Non login panel la: $(grep '^ADMIN_USER=' /etc/galaxy.env | cut -d= -f2)"
echo " NOUVO MODPAS PANEL LA:    $NEWPASS"
echo " Ekri l kote ki an sekirite. Chanje l nan System -> Owner login."
echo "================================================================"
