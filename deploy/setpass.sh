#!/usr/bin/env bash
# Mete yon nouvo non login + modpas pwopriyetè panel la (si w bliye l).
# Itilizasyon: curl -sLo p raw.githubusercontent.com/bentechtvstick-glitch/bentech-backend/main/deploy/setpass.sh ; bash p
set -euo pipefail
read -rp "Nouvo non login (egz: admin): " U
[ -n "$U" ] || { echo "Non an vid."; exit 1; }
while true; do
  read -rsp "Nouvo modpas (12 karakte oswa plis, let ak chif): " P; echo
  read -rsp "Tape l ankò: " P2; echo
  [ "$P" = "$P2" ] || { echo "De modpas yo pa menm. Eseye ankò."; continue; }
  [ ${#P} -ge 12 ] || { echo "Twò kout (12 karakte oswa plis)."; continue; }
  break
done
ENV=/etc/galaxy.env
grep -v -E '^(ADMIN_USER|ADMIN_PASS|RESET_OWNER)=' "$ENV" > "$ENV.new" || true
printf 'ADMIN_USER=%s\nADMIN_PASS=%s\n' "$U" "$P" >> "$ENV.new"
mv "$ENV.new" "$ENV"; chmod 600 "$ENV"
systemctl stop galaxy
# Retire login pwopriyetè ki te chanje nan panel la, pou nouvo a pran plas li
DB=$(grep '^DB_FILE=' "$ENV" | cut -d= -f2-); DB=${DB:-/var/lib/galaxy/data/db.json}
node -e 'const fs=require("fs"),f=process.argv[1];const d=JSON.parse(fs.readFileSync(f,"utf8"));delete d.ownerAuth;fs.writeFileSync(f,JSON.stringify(d,null,2))' "$DB"
chown galaxy:galaxy "$DB"
systemctl start galaxy
sleep 2; systemctl is-active galaxy
echo "FINI. Konekte nan panel la ak: $U (ak modpas ou fèk chwazi a)."
