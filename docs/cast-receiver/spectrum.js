/**
 * Spektrum suara di layar TV: batang-batang yang bergerak mengikuti audio siaran, simetris
 * kiri-kanan dengan nada rendah di tengah, senada dengan ikon mikrofon di logo.
 *
 * Supaya mulus di TV yang lambat, batang tidak digambar ulang tiap frame: tiap batang adalah
 * elemen sendiri dan yang berubah hanya transform scaleY. JavaScript memperbarui tingginya
 * ~30× per detik, dan transisi CSS (dijalankan compositor/GPU, bukan JavaScript) menghaluskan
 * gerakan di antaranya.
 *
 * Audio dianalisis lewat Web Audio dari elemen <audio> yang diputar CAF. Server stream harus
 * mengizinkan halaman ini lewat header CORS (AzuraCast memantulkan Origin); tanpa itu Web Audio
 * hanya menerima sunyi, dan memasang crossOrigin malah membuat stream gagal dimuat. Karena itu
 * izinnya dicek dulu (probeCors). Kalau tidak bisa, spektrum disembunyikan dan audio tetap jalan.
 */

/** Jumlah band per sisi; total batang di layar = 2 × BANDS. */
const BANDS = 18;
const MIN_HZ = 60;
const MAX_HZ = 10000;
/** Khotbah dan lagu lemah di nada tinggi; dinaikkan sedikit supaya batang di tepi tidak diam. */
const HIGH_BAND_BOOST = 0.8;
/** Selang pembaruan tinggi batang. Sinkron dengan `transition` .spectrum-bar di receiver.css. */
const UPDATE_INTERVAL_MS = 33;
/** Batang naik seketika dan turun dengan konstanta waktu ini (detik), berapa pun frame rate-nya. */
const RELEASE_SECONDS = 0.12;
/** Tinggi minimum (bagian dari tinggi penuh): saat sunyi batang tampil sebagai titik. */
const MIN_LEVEL = 0.08;

/**
 * Rentang bin FFT [start, end) untuk tiap band, berjarak logaritmik seperti pendengaran.
 * Rentang bersambung, tidak kosong, dan selalu di dalam [1, binCount].
 */
export function bandRanges(binCount, sampleRate, bands = BANDS, minHz = MIN_HZ, maxHz = MAX_HZ) {
  const hzPerBin = sampleRate / 2 / binCount;
  const ranges = [];
  let start = Math.min(binCount - 1, Math.max(1, Math.floor(minHz / hzPerBin)));
  for (let i = 0; i < bands; i += 1) {
    const edgeHz = minHz * Math.pow(maxHz / minHz, (i + 1) / bands);
    const end = Math.min(binCount, Math.max(start + 1, Math.round(edgeHz / hzPerBin)));
    ranges.push([start, end]);
    start = Math.min(end, binCount - 1);
  }
  return ranges;
}

/** Tinggi batang 0..1 dari rata-rata data frekuensi (0..255) satu band. */
export function bandLevel(frequencyData, range, index, bands = BANDS) {
  const [start, end] = range;
  let sum = 0;
  for (let bin = start; bin < end; bin += 1) sum += frequencyData[bin];
  const average = sum / (end - start) / 255;
  const boost = 1 + HIGH_BAND_BOOST * (index / Math.max(1, bands - 1));
  return Math.min(1, average * boost);
}

/** True kalau server stream mengizinkan halaman ini membaca audionya (header CORS). */
export async function probeCors(url, timeoutMs = 3000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { mode: 'cors', cache: 'no-store', signal: controller.signal });
    return response.ok;
  } catch (error) {
    return false;
  } finally {
    clearTimeout(timer);
    controller.abort(); // Cukup header-nya; jangan ikut mengunduh stream.
  }
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Membuat batang-batang spektrum di dalam container. Sebelum audio tersambung tampil sebagai
 * deretan titik.
 *
 * @param {HTMLElement} container
 * @param {HTMLMediaElement} mediaElement elemen yang dipakai CAF (setMediaElement).
 */
export function createSpectrum(container, mediaElement) {
  // Batang kiri dan kanan untuk band yang sama: nada rendah di tengah, makin tinggi makin ke tepi.
  const bars = Array.from({ length: BANDS * 2 }, () => {
    const bar = document.createElement('span');
    bar.className = 'spectrum-bar';
    container.appendChild(bar);
    return bar;
  });
  const barPairs = Array.from({ length: BANDS }, (_, band) => [bars[BANDS - 1 - band], bars[BANDS + band]]);
  const levels = new Float32Array(BANDS);
  let audioContext = null;
  let analyser = null;
  let frequencyData = null;
  let ranges = [];
  let preparing = null;
  let lastUpdate = 0;

  function render() {
    for (let band = 0; band < BANDS; band += 1) {
      const transform = `scaleY(${Math.max(MIN_LEVEL, levels[band]).toFixed(3)})`;
      barPairs[band][0].style.transform = transform;
      barPairs[band][1].style.transform = transform;
    }
  }

  function frame(now) {
    requestAnimationFrame(frame);
    const elapsed = now - lastUpdate;
    if (elapsed < UPDATE_INTERVAL_MS) return;
    lastUpdate = now;
    const release = 1 - Math.exp(-Math.min(elapsed, 250) / 1000 / RELEASE_SECONDS);
    analyser.getByteFrequencyData(frequencyData);
    for (let i = 0; i < BANDS; i += 1) {
      const target = bandLevel(frequencyData, ranges[i], i);
      levels[i] = target > levels[i] ? target : levels[i] + (target - levels[i]) * release;
    }
    render();
  }

  async function connect(url) {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass || !(await probeCors(url))) return false;
    mediaElement.crossOrigin = 'anonymous';

    const context = new AudioContextClass();
    // Sekali elemen tersambung ke AudioContext, suaranya hanya keluar lewat context itu. Kalau
    // context tidak boleh berjalan (kebijakan autoplay), jangan sambungkan: TV akan bisu.
    await Promise.race([context.resume(), wait(1000)]);
    if (context.state !== 'running') {
      context.close();
      return false;
    }
    audioContext = context;
    analyser = context.createAnalyser();
    analyser.fftSize = 2048;
    // Penghalusan utama dilakukan di sini (RELEASE_SECONDS) dan oleh transisi CSS.
    analyser.smoothingTimeConstant = 0.5;
    analyser.minDecibels = -95;
    analyser.maxDecibels = -25;
    context.createMediaElementSource(mediaElement).connect(analyser);
    analyser.connect(context.destination);
    frequencyData = new Uint8Array(analyser.frequencyBinCount);
    ranges = bandRanges(analyser.frequencyBinCount, context.sampleRate);
    requestAnimationFrame(frame);
    return true;
  }

  render();

  return {
    /**
     * Dipanggil sebelum stream dimuat (crossOrigin harus terpasang sebelum src). Hanya sekali;
     * panggilan berikutnya memakai hasil yang sama.
     */
    prepare(url) {
      if (!preparing) {
        preparing = connect(url).then((connected) => {
          container.hidden = !connected;
          return connected;
        });
      }
      return preparing;
    },
    /** AudioContext bisa disuspend sistem; lanjutkan saat audio diputar lagi. */
    resume() {
      if (audioContext && audioContext.state === 'suspended') audioContext.resume();
    },
  };
}
