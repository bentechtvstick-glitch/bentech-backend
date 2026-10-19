// Kont admin yo ak dwa yo (wòl).
//  - Pwopriyetè a (ADMIN_USER / ADMIN_PASS nan /etc/galaxy.env): tout bagay.
//  - "Admin Users" panel la: chak moun konekte ak imèl li + yon modpas pwopriyetè a ba li.
//      Super Administrator → tout bagay, menm jan ak pwopriyetè a, sof nòt prive pwopriyetè a.
//      Administrator       → tout bagay, sof: kont admin yo, nòt prive yo, kopi done yo (backup/restore).
//      Support             → wè tout bagay; ka sèlman: jere kliyan (san efase), TV yo (kòmand, playlist, tès), AI a.
//      Viewer              → wè sèlman, pa ka chanje anyen.
// Règ yo aplike sou sèvè a (pa sèlman nan panel la), pou yo pa ka kontoune.
import crypto from "node:crypto";
import jwt from "jsonwebtoken";

const ROLES = ["Super Administrator", "Administrator", "Support", "Viewer"];

export function hashPassword(pw) {
  const salt = crypto.randomBytes(16).toString("hex");
  const h = crypto.scryptSync(String(pw), salt, 64).toString("hex");
  return `scrypt$${salt}$${h}`;
}
export function checkPassword(pw, stored) {
  const [kind, salt, h] = String(stored || "").split("$");
  if (kind !== "scrypt" || !salt || !h) return false;
  const a = Buffer.from(h, "hex"), b = crypto.scryptSync(String(pw), salt, 64);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/** Moun sa a konekte ak imèl li? Voye { email, name, role } si modpas la bon epi kont lan aktif. */
export function findAdminLogin(db, username, password) {
  const u = String(username || "").trim().toLowerCase();
  if (!u || !password) return null;
  const x = (db.data.adminUsers || []).find((a) => String(a.email || "").trim().toLowerCase() === u);
  if (!x || !x.passwordHash) return null;
  if (String(x.status || "Active").toLowerCase() !== "active") return null;
  if (!checkPassword(password, x.passwordHash)) return null;
  return { email: x.email, name: x.name || x.email, role: ROLES.includes(x.role) ? x.role : "Viewer" };
}

// ---- Kont pwopriyetè a ----
// Pa defo: ADMIN_USER / ADMIN_PASS nan /etc/galaxy.env. Depi pwopriyetè a chanje yo nan panel la,
// yo sove (modpas la an hash) nan baz done a epi sa ki nan galaxy.env pa mache ankò.
// Si w bliye modpas la: mete RESET_OWNER=1 nan /etc/galaxy.env, redemare, konekte ak ADMIN_USER/ADMIN_PASS, epi retire RESET_OWNER.
const envOwner = () => ({ user: process.env.ADMIN_USER || "admin", pass: process.env.ADMIN_PASS || "changeme" });
export function ownerAuth(db) {
  if (process.env.RESET_OWNER === "1") return null;
  const o = db.data.ownerAuth;
  return o && o.user && o.passwordHash ? o : null;
}
const eq = (a, b) => { const x = Buffer.from(String(a)), y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
/** Non login + modpas sa yo se pou pwopriyetè a? Voye { user, ov } (ov = vèsyon kont lan) oswa null. */
export function checkOwnerLogin(db, username, password) {
  const o = ownerAuth(db);
  if (o) return eq(String(username || "").trim(), o.user) && checkPassword(password, o.passwordHash) ? { user: o.user, ov: o.changedAt } : null;
  const e = envOwner();
  return eq(username || "", e.user) && eq(password || "", e.pass) ? { user: e.user, ov: "" } : null;
}
/** Token sa a se pou pwopriyetè a kounye a? (yon token ansyen kont lan pa mache ankò apre yon chanjman) */
function isOwnerToken(db, tok) {
  const o = ownerAuth(db);
  if (o) return tok.owner === 1 && tok.sub === o.user && tok.ov === o.changedAt;
  return tok.sub === envOwner().user && !tok.ov;
}

// Chemen sekrè yo
const OWNER_ACC = /^\/api\/galaxy\/owner-account/;
const NOTES = /^\/api\/galaxy\/notes/;
const BACKUP = /^\/api\/galaxy\/(backup|backups|restore)(\/|$)/;
const ADMINS = /^\/api\/admin-users(\/|$)/;
// Sa "Support" ka chanje
const SUPPORT_WRITE = [
  /^\/api\/galaxy\/devices\/[^/]+\/commands$/,
  /^\/api\/galaxy\/devices\/[^/]+\/playlists(\/[^/]+(\/test)?)?$/,
  /^\/api\/galaxy\/devices\/[^/]+$/,
  /^\/api\/galaxy\/xtream\/test$/,
  /^\/api\/customers(\/[^/]+)?$/,
  /^\/api\/galaxy\/customers\/sync-provider$/,
  /^\/api\/galaxy\/(ai|watch\/analyze|watch\/dismiss|alerts\/seen)$/,
];

/** Kisa wòl sa a ka fè sou demann sa a? Voye yon mesaj erè, oswa null si l otorize. */
export function denyReason(role, method, p) {
  const write = method !== "GET" && method !== "HEAD" && method !== "OPTIONS";
  if (role === "owner") return null;
  if (NOTES.test(p)) return "Only the owner can open the private notes.";
  if (OWNER_ACC.test(p)) return "Only the owner can change the owner login.";
  if (/^\/api\/galaxy\/ai-key/.test(p) && write) return "Only the owner can change the AI key.";
  if (role === "Super Administrator") return null;
  if (BACKUP.test(p)) return "Only the owner can download or restore backups.";
  if (ADMINS.test(p) && (write || role !== "Administrator")) return "Only the owner can manage admin accounts.";
  if (role === "Administrator") return null;
  if (/^\/api\/galaxy\/notify/.test(p)) return "Your account cannot open the phone notification settings.";
  if (!write) return null; // Support ak Viewer ka gade
  if (role === "Support") {
    if (method === "DELETE" && !/\/playlists\//.test(p)) return "Your account (Support) cannot delete.";
    if (SUPPORT_WRITE.some((r) => r.test(p))) return null;
    return "Your account (Support) cannot change this.";
  }
  return "Your account (Viewer) can only look, not change.";
}

/**
 * Middleware: li JWT a (si genyen), jwenn wòl moun nan (ajou, soti nan baz done a), epi bloke sa l pa gen dwa fè.
 * Route TV yo (/api/devices/<mac>/…, /api/devices/register), login ak /api/health pa konsène.
 */
export function accessControl(db, secret) {
  return (req, res, next) => {
    const p = req.path;
    if (!p.startsWith("/api/") || p.startsWith("/api/auth/") || p === "/api/health" || p === "/api/version") return next();
    if (/^\/api\/devices\/(register|[^/]+\/.+)$/.test(p)) return next(); // route TV yo
    const h = req.headers.authorization || (req.query?.t ? "Bearer " + req.query.t : "");
    if (!h.startsWith("Bearer ")) return next(); // authenticate ap refize l pi devan
    let tok; try { tok = jwt.verify(h.slice(7), secret); } catch { return next(); }
    let role;
    if (tok.uid) {
      // Yon kont "Admin Users": tcheke l toujou egziste, li aktif, epi pran wòl li kounye a
      const x = (db.data.adminUsers || []).find((a) => String(a.email || "").toLowerCase() === String(tok.uid).toLowerCase());
      if (!x || !x.passwordHash || String(x.status || "Active").toLowerCase() !== "active") return res.status(401).json({ ok: false, error: "Token expired or invalid" });
      role = ROLES.includes(x.role) ? x.role : "Viewer";
    } else if (isOwnerToken(db, tok)) role = "owner";
    else if (ownerAuth(db) || tok.sub === envOwner().user) return res.status(401).json({ ok: false, error: "Token expired or invalid" }); // ansyen sesyon pwopriyetè a
    else role = "Viewer";
    req.role = role;
    const why = denyReason(role, req.method, p);
    if (why) return res.status(403).json({ ok: false, error: why, forbidden: true });
    next();
  };
}
