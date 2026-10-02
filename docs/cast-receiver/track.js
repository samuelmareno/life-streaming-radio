/**
 * Memecah metadata ICY "Artis - Judul" di " - " pertama; kedua sisi wajib berisi.
 *
 * Aturannya HARUS sama persis dengan parseIcyTrack() di Android
 * (app/src/main/java/.../extensions/TrackMetadata.kt) dan parseICYTrack(_:) di iOS,
 * supaya TV, notifikasi HP, dan aplikasi menampilkan judul yang sama.
 *
 * @param {string|null|undefined} raw
 * @returns {{artist: string, title: string} | null} null kalau format tidak dikenali.
 */
export function parseIcyTrack(raw) {
  const text = (raw == null ? '' : String(raw)).trim();
  if (!text) return null;

  const separator = text.indexOf(' - ');
  if (separator < 0) return null;

  const artist = text.slice(0, separator).trim();
  const title = text.slice(separator + 3).trim();
  if (!artist || !title) return null;

  return { artist, title };
}

/**
 * Judul & artis untuk ditampilkan. Teks tanpa pemisah tampil apa adanya dengan nama
 * stasiun sebagai artis; teks kosong menampilkan nama stasiun.
 */
export function nowPlayingFrom(songText, station) {
  const track = parseIcyTrack(songText);
  if (track) return track;
  const text = (songText == null ? '' : String(songText)).trim();
  return text
    ? { title: text, artist: station.artist }
    : { title: station.title, artist: station.artist };
}
