// TV admin nan efase men ki toujou limen sou ekran aktivasyon an re-anrejistre poukont li.
// Li parèt kòm yon TV tou nèf «ap tann aktivasyon» (san kliyan) jiskaske admin nan ba l yon playlist ankò.
export function isGhost(data, d) {
  return !!(d?.mac && (data.deletedDevices || []).includes(d.mac) && !((data.devicePlaylists || {})[d.mac] || []).length);
}
// Tout aparèy yo konte (TV ki sou ekran aktivasyon an parèt kòm "ap tann aktivasyon")
export const visibleDevices = (data) => data.devices || [];
