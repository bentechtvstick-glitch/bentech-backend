// Poukisa panel la redemare: mizajou, sèvè a te etenn/redemare, redemaraj manyèl, oswa yon erè (crash).
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const dir = path.dirname(path.resolve(process.env.DB_FILE || "./db.json"));
const EXIT = path.join(dir, ".last-exit.json");
const START = path.join(dir, ".last-start.json");
const read = (f) => { try { return JSON.parse(fs.readFileSync(f, "utf8")); } catch { return null; } };
const write = (f, v) => { try { fs.writeFileSync(f, JSON.stringify(v)); } catch {} };

function gitSha() {
  try {
    const head = fs.readFileSync(".git/HEAD", "utf8").trim();
    if (!head.startsWith("ref:")) return head.slice(0, 7);
    const ref = head.slice(5).trim();
    try { return fs.readFileSync(path.join(".git", ref), "utf8").trim().slice(0, 7); } catch {}
    const packed = fs.readFileSync(".git/packed-refs", "utf8").split("\n").find((l) => l.endsWith(" " + ref));
    return packed ? packed.slice(0, 7) : "";
  } catch { return ""; }
}

const prevExit = read(EXIT), prevStart = read(START), sha = gitSha();
try { fs.unlinkSync(EXIT); } catch {}
let reason, detail = "";
if (os.uptime() < 600) reason = "reboot"; // tout machin nan fèk limen (Hetzner, kouran, oswa yon reboot)
else if (prevStart?.sha && sha && prevStart.sha !== sha) { reason = "update"; detail = `${prevStart.sha} → ${sha}`; }
else if (prevExit?.reason === "signal") reason = "manual";
else if (prevExit?.reason === "crash") { reason = "crash"; detail = String(prevExit.err || "").slice(0, 300); }
else reason = prevStart ? "killed" : "first";
export const lastStart = { reason, detail, at: new Date().toISOString(), sha, prevAt: prevExit?.at || null };
write(START, { sha, at: lastStart.at });

let marked = false;
export function markExit(r, err) {
  if (marked) return; marked = true;
  write(EXIT, { reason: r, err: err ? String(err?.stack || err).slice(0, 600) : "", at: new Date().toISOString() });
}
process.on("uncaughtException", (e) => { console.error("Uncaught:", e); markExit("crash", e); process.exit(1); });
process.on("unhandledRejection", (e) => { console.error("Unhandled rejection:", e); });
