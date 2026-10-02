/**
 * Menjaga layar TV tetap menyala selama radio diputar, supaya Google TV tidak masuk screensaver
 * dan tampilan ini (logo, judul, spektrum) tetap terlihat. Kartu screensaver Google TV selalu
 * memakai ikon Cast dan teksnya sulit dibaca di atas foto terang; aplikasi Cast tidak bisa
 * mengubahnya. Android TV mengizinkan ini untuk audio kalau tampilannya bergerak (spektrum).
 * Saat jeda atau berhenti kuncinya dilepas, jadi screensaver tetap jalan seperti biasa.
 *
 * Memakai Screen Wake Lock API; kalau TV tidak mendukungnya, tidak terjadi apa-apa.
 */
export function createScreenKeeper({ nav = globalThis.navigator, doc = globalThis.document } = {}) {
  let wanted = false;
  let lock = null;
  let requesting = false;

  async function acquire() {
    if (!wanted || lock || requesting) return;
    if (!nav || !nav.wakeLock || doc.visibilityState !== 'visible') return;
    requesting = true;
    try {
      // Tidak ditunggu oleh pemutaran: di sebagian TV promise ini tidak pernah selesai.
      const sentinel = await nav.wakeLock.request('screen');
      if (!wanted) {
        sentinel.release();
        return;
      }
      lock = sentinel;
      // Sistem bisa melepasnya sendiri, mis. saat halaman tersembunyi; diminta lagi saat tampil.
      sentinel.addEventListener('release', () => {
        if (lock === sentinel) lock = null;
      });
    } catch (error) {
      console.warn('Layar tidak bisa dijaga tetap menyala', error);
    } finally {
      requesting = false;
    }
  }

  doc.addEventListener('visibilitychange', () => acquire());

  return {
    /** Radio diputar atau sedang buffering: jangan masuk screensaver. */
    keepOn() {
      wanted = true;
      acquire();
    },
    /** Radio dijeda atau berhenti: screensaver boleh jalan lagi. */
    allowOff() {
      wanted = false;
      if (lock) {
        const held = lock;
        lock = null;
        held.release();
      }
    },
  };
}
