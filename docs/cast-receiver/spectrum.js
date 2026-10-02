/**
 * Spektrum suara di layar TV: batang-batang yang bergerak mengikuti audio siaran, simetris
 * kiri-kanan dengan nada rendah di tengah, senada dengan ikon mikrofon di logo.
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
const HIGH_BAND_BOOST = 0.6;
/** Batang naik seketika, turun perlahan (porsi selisih per frame). */
const RELEASE = 0.18;

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
 * Menggambar spektrum di canvas. Sebelum audio tersambung tampil sebagai deretan titik.
 *
 * @param {HTMLCanvasElement} canvas
 * @param {HTMLMediaElement} mediaElement elemen yang dipakai CAF (setMediaElement).
 */
export function createSpectrum(canvas, mediaElement) {
  const graphics = canvas.getContext('2d');
  const levels = new Float32Array(BANDS);
  let audioContext = null;
  let analyser = null;
  let frequencyData = null;
  let ranges = [];
  let preparing = null;

  function resize() {
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(canvas.clientWidth * ratio);
    canvas.height = Math.round(canvas.clientHeight * ratio);
  }

  function bar(x, y, width, height) {
    graphics.beginPath();
    if (graphics.roundRect) {
      graphics.roundRect(x, y, width, height, width / 2);
    } else {
      graphics.rect(x, y, width, height);
    }
    graphics.fill();
  }

  function paint() {
    const { width, height } = canvas;
    graphics.clearRect(0, 0, width, height);
    const total = BANDS * 2;
    const gap = (width / total) * 0.4;
    const barWidth = (width - gap * (total - 1)) / total;
    // Ujung batang lebih terang, tengah ungu aplikasi.
    const gradient = graphics.createLinearGradient(0, 0, 0, height);
    gradient.addColorStop(0, '#c4b5fd');
    gradient.addColorStop(0.5, '#7640f6');
    gradient.addColorStop(1, '#c4b5fd');
    graphics.fillStyle = gradient;
    for (let slot = 0; slot < total; slot += 1) {
      // Nada rendah di tengah, makin tinggi makin ke tepi.
      const band = slot < BANDS ? BANDS - 1 - slot : slot - BANDS;
      const barHeight = Math.max(barWidth, levels[band] * height);
      bar(slot * (barWidth + gap), (height - barHeight) / 2, barWidth, barHeight);
    }
  }

  function frame() {
    analyser.getByteFrequencyData(frequencyData);
    for (let i = 0; i < BANDS; i += 1) {
      const target = bandLevel(frequencyData, ranges[i], i);
      levels[i] = target > levels[i] ? target : levels[i] + (target - levels[i]) * RELEASE;
    }
    paint();
    requestAnimationFrame(frame);
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
    analyser.smoothingTimeConstant = 0.7;
    analyser.minDecibels = -85;
    analyser.maxDecibels = -25;
    context.createMediaElementSource(mediaElement).connect(analyser);
    analyser.connect(context.destination);
    frequencyData = new Uint8Array(analyser.frequencyBinCount);
    ranges = bandRanges(analyser.frequencyBinCount, context.sampleRate);
    requestAnimationFrame(frame);
    return true;
  }

  resize();
  paint();
  window.addEventListener('resize', () => {
    resize();
    paint();
  });

  return {
    /**
     * Dipanggil sebelum stream dimuat (crossOrigin harus terpasang sebelum src). Hanya sekali;
     * panggilan berikutnya memakai hasil yang sama.
     */
    prepare(url) {
      if (!preparing) {
        preparing = connect(url).then((connected) => {
          canvas.hidden = !connected;
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
