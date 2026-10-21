// TV admin nan efase men ki toujou limen sou ekran aktivasyon an re-anrejistre poukont li.
// Li pa konte kòm yon aparèy (lis, dashboard, sistèm, AI) jiskaske admin nan ba l yon playlist ankò.
export function isGhost(data, d) {
  return !!(d?.mac && (data.deletedDevices || []).includes(d.mac) && !((data.devicePlaylists || {})[d.mac] || []).length);
}
export const visibleDevices = (data) => (data.devices || []).filter((d) => !isGhost(data, d));
