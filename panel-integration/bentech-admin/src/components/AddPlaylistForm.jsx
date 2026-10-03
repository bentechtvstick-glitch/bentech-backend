// ---------------------------------------------------------------------------
// Galaxy TV Stick — Fòm "Ajoute Playlist" pou bentech-admin (React + Vite)
//
// Kijan pou itilize l:
//   import AddPlaylistForm from "../components/AddPlaylistForm";
//   <AddPlaylistForm onDone={() => refreshList()} onCancel={() => setOpen(false)} />
//
// Li itilize instans axios ou a nan src/services/api.js
// (baseURL = https://bentech-backend.onrender.com/api).
// Route /api/galaxy/* yo mande JWT: api.js dwe voye "Authorization: Bearer <token>"
// (token /api/auth/login bay la). Si api.js ou a pa fè "export default", chanje import la anba a.
// ---------------------------------------------------------------------------
import { useEffect, useState } from "react";
import api from "../services/api";

const formatMac = (value) => {
  const hex = value.toUpperCase().replace(/[^0-9A-F]/g, "").slice(0, 12);
  return hex.match(/.{1,2}/g)?.join(":") ?? "";
};
const isFullMac = (mac) => /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(mac);

const timeAgo = (ms) => {
  if (!ms) return "—";
  const min = Math.round((Date.now() - ms) / 60000);
  if (min < 1) return "kounye a";
  if (min < 60) return `${min} min de sa`;
  const h = Math.round(min / 60);
  if (h < 24) return `${h} è de sa`;
  return `${Math.round(h / 24)} jou de sa`;
};

const EMPTY = { name: "", server: "", username: "", password: "" };

const errorText = (err, fallback) =>
  err?.response?.status === 401
    ? "Sesyon ou fini oswa panel la pa voye token an. Dekonekte epi rekonekte nan panel la."
    : err?.response?.data?.error || fallback;

export default function AddPlaylistForm({ initialMac = "", onDone, onCancel }) {
  const [mac, setMac] = useState(formatMac(initialMac));
  const [device, setDevice] = useState(null);
  const [lookupError, setLookupError] = useState("");
  const [looking, setLooking] = useState(false);
  const [form, setForm] = useState(EMPTY);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  // Lè MAC la konplè, chèche aparèy la pou montre Device Key a
  useEffect(() => {
    setDevice(null);
    setLookupError("");
    if (!isFullMac(mac)) return;
    let cancelled = false;
    setLooking(true);
    const t = setTimeout(async () => {
      try {
        const { data } = await api.get("/galaxy/devices/lookup", { params: { mac } });
        if (!cancelled) setDevice(data);
      } catch (e) {
        if (!cancelled) setLookupError(errorText(e, "Aparèy pa jwenn"));
      } finally {
        if (!cancelled) setLooking(false);
      }
    }, 350);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [mac]);

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const canSubmit =
    device && form.name.trim() && form.username.trim() && form.password.trim() && !saving;

  const submit = async (e) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSaving(true);
    setError("");
    setSuccess("");
    try {
      const { data } = await api.post(`/galaxy/devices/${encodeURIComponent(mac)}/playlists`, {
        ...form,
        deviceKey: device.deviceKey,
      });
      setDevice(data.device);
      setSuccess(`Playlist "${data.playlist.name}" ajoute. L ap parèt sou TV a nan kèk segonn.`);
      setForm(EMPTY);
      onDone?.(data);
    } catch (err) {
      setError(errorText(err, "Pa ka sove playlist la"));
    } finally {
      setSaving(false);
    }
  };

  const removePlaylist = async (id) => {
    if (!window.confirm("Retire playlist sa a sou aparèy la?")) return;
    try {
      await api.delete(`/galaxy/devices/${encodeURIComponent(mac)}/playlists/${id}`);
      setDevice((d) => ({ ...d, playlists: d.playlists.filter((p) => p.id !== id) }));
    } catch (err) {
      setError(errorText(err, "Pa ka retire playlist la"));
    }
  };

  const cancel = () => {
    setMac("");
    setForm(EMPTY);
    setDevice(null);
    setError("");
    setSuccess("");
    onCancel?.();
  };

  return (
    <form onSubmit={submit} style={styles.card}>
      <h2 style={styles.title}>Ajoute Playlist</h2>

      {/* MAC ADDRESS */}
      <label style={styles.label}>MAC Address</label>
      <input
        style={{ ...styles.input, ...styles.mono }}
        placeholder="3A:7F:12:C4:9E:05"
        value={mac}
        onChange={(e) => setMac(formatMac(e.target.value))}
        autoFocus
      />

      {/* Aparèy la parèt lè MAC la bon */}
      {looking && <div style={styles.muted}>Ap chèche aparèy la…</div>}
      {lookupError && <div style={styles.error}>{lookupError}</div>}
      {device && (
        <div style={styles.device}>
          <div style={styles.row}>
            <span style={styles.muted}>Device Key</span>
            <strong style={{ ...styles.mono, fontSize: 20, letterSpacing: 2 }}>{device.deviceKey || "—"}</strong>
          </div>
          <div style={styles.row}>
            <span style={styles.muted}>Estati</span>
            <span style={{ ...styles.badge, background: statusColor(device.status) }}>{device.status}</span>
          </div>
          <div style={styles.row}>
            <span style={styles.muted}>Aparèy</span>
            <span>{device.model || "—"} · v{device.appVersion || "?"}</span>
          </div>
          <div style={styles.row}>
            <span style={styles.muted}>Dènye fwa</span>
            <span>{timeAgo(device.lastSeen)}</span>
          </div>

          {device.playlists?.length > 0 && (
            <div style={{ marginTop: 10 }}>
              <div style={styles.muted}>Playlist ki deja la</div>
              {device.playlists.map((p) => (
                <div key={p.id} style={styles.playlist}>
                  <span>
                    <strong>{p.name}</strong> <span style={styles.muted}>· {p.username}</span>
                  </span>
                  <button type="button" style={styles.linkBtn} onClick={() => removePlaylist(p.id)}>
                    Retire
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* PLAYLIST */}
      <fieldset disabled={!device} style={{ border: 0, padding: 0, margin: 0, opacity: device ? 1 : 0.5 }}>
        <label style={styles.label}>Non Playlist</label>
        <input style={styles.input} placeholder="Egz: Salon" value={form.name} onChange={set("name")} />

        <label style={styles.label}>
          Sèvè (DNS) <span style={styles.muted}>— opsyonèl si app la gen yon sèvè pa defo</span>
        </label>
        <input style={styles.input} placeholder="http://dns.example.com:8080" value={form.server} onChange={set("server")} />

        <div style={{ display: "flex", gap: 12 }}>
          <div style={{ flex: 1 }}>
            <label style={styles.label}>Username</label>
            <input style={styles.input} value={form.username} onChange={set("username")} autoComplete="off" />
          </div>
          <div style={{ flex: 1 }}>
            <label style={styles.label}>Password</label>
            <input style={styles.input} value={form.password} onChange={set("password")} autoComplete="off" />
          </div>
        </div>
      </fieldset>

      {error && <div style={styles.error}>{error}</div>}
      {success && <div style={styles.success}>{success}</div>}

      <div style={styles.actions}>
        <button type="button" style={styles.cancel} onClick={cancel}>
          Cancel
        </button>
        <button type="submit" style={{ ...styles.submit, opacity: canSubmit ? 1 : 0.5 }} disabled={!canSubmit}>
          {saving ? "Ap sove…" : "Submit"}
        </button>
      </div>
    </form>
  );
}

const statusColor = (s) =>
  ({ active: "#1faa59", pending: "#c98a00", blocked: "#d6334b", expired: "#6b6f80" })[s] || "#6b6f80";

const styles = {
  card: { background: "#141a2e", color: "#fff", padding: 24, borderRadius: 14, maxWidth: 560, width: "100%", boxSizing: "border-box" },
  title: { margin: "0 0 16px", fontSize: 22 },
  label: { display: "block", fontSize: 13, color: "#9aa3c7", margin: "14px 0 6px" },
  input: { width: "100%", boxSizing: "border-box", padding: "11px 12px", borderRadius: 8, border: "1px solid #2c3558", background: "#0b0e1a", color: "#fff", fontSize: 15 },
  mono: { fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" },
  muted: { color: "#9aa3c7", fontSize: 13 },
  device: { marginTop: 12, padding: 14, borderRadius: 10, background: "#0b0e1a", border: "1px solid #2c3558" },
  row: { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "4px 0" },
  badge: { padding: "2px 10px", borderRadius: 999, fontSize: 12, fontWeight: 700, textTransform: "uppercase" },
  playlist: { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "6px 0", borderTop: "1px solid #1f2745" },
  linkBtn: { background: "none", border: 0, color: "#ff6b81", cursor: "pointer", fontSize: 13 },
  error: { marginTop: 12, color: "#ff6b81", fontSize: 14 },
  success: { marginTop: 12, color: "#3ddc84", fontSize: 14 },
  actions: { display: "flex", justifyContent: "flex-end", gap: 10, marginTop: 20 },
  cancel: { padding: "10px 18px", borderRadius: 8, border: "1px solid #2c3558", background: "transparent", color: "#fff", cursor: "pointer" },
  submit: { padding: "10px 22px", borderRadius: 8, border: 0, background: "#7c4dff", color: "#fff", fontWeight: 700, cursor: "pointer" },
};
