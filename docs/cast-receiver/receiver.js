import { createSpectrum } from './spectrum.js';
import { nowPlayingFrom } from './track.js';

// Sama dengan aplikasi: NOWPLAYING_URL (Android, gradle.properties) & Constants.nowPlayingURL (iOS).
const NOWPLAYING_URL = 'https://a2.siar.us/api/nowplaying_static/life_radio.json';
const POLL_INTERVAL_MS = 15000;
const STATION = Object.freeze({ title: 'Life Streaming Radio', artist: 'House of Life' });
/**
 * Ikon aplikasi (bulat, 512×512) di metadata media: gambar untuk kartu screensaver Google TV,
 * Google Home, dan kontrol Cast di HP lain. Harus URL publik.
 */
const ICON = Object.freeze({ url: new URL('cast-icon.png', location.href).href, size: 512 });
// Sama dengan RADIO_URL (Android) & Constants.radioURL (iOS). Hanya untuk pratinjau: di TV,
// alamat stream datang dari HP lewat permintaan LOAD.
const PREVIEW_STREAM_URL = 'https://a2.siar.us/listen/life_radio/radio.mp3';

const ui = {
  status: document.getElementById('status'),
  title: document.getElementById('title'),
  artist: document.getElementById('artist'),
  playback: document.getElementById('playback'),
  player: document.getElementById('player'),
  spectrum: document.getElementById('spectrum'),
};

let current = { title: STATION.title, artist: STATION.artist };

function renderNowPlaying(nowPlaying) {
  ui.title.textContent = nowPlaying.title;
  ui.artist.textContent = nowPlaying.artist;
}

function renderStationStatus(isOnline) {
  ui.status.hidden = false;
  ui.status.textContent = isOnline ? '● ON AIR' : '● OFFLINE';
  ui.status.classList.toggle('offline', !isOnline);
}

function renderPlayback(text, blinking = false) {
  ui.playback.textContent = text;
  ui.playback.classList.toggle('blinking', blinking);
}

function songTextOf(data) {
  const song = data && data.now_playing && data.now_playing.song;
  return song ? song.text : null;
}

/** Membaca status stasiun; judul baru diteruskan ke onNowPlaying hanya kalau berubah. */
async function pollStation(onNowPlaying) {
  try {
    const response = await fetch(NOWPLAYING_URL, { cache: 'no-store' });
    if (!response.ok) return;
    const data = await response.json();
    renderStationStatus(Boolean(data.is_online));
    const next = nowPlayingFrom(songTextOf(data), STATION);
    if (next.title !== current.title || next.artist !== current.artist) {
      current = next;
      renderNowPlaying(current);
      onNowPlaying(current);
    }
  } catch (error) {
    // API tidak terjangkau: biarkan tampilan terakhir, coba lagi di putaran berikutnya.
    console.warn('Gagal membaca status stasiun', error);
  }
}

function startPolling(onNowPlaying) {
  pollStation(onNowPlaying);
  setInterval(() => pollStation(onNowPlaying), POLL_INTERVAL_MS);
}

function startReceiver() {
  const messages = cast.framework.messages;
  const events = cast.framework.events;
  const context = cast.framework.CastReceiverContext.getInstance();
  const playerManager = context.getPlayerManager();

  playerManager.setMediaElement(ui.player);
  const spectrum = createSpectrum(ui.spectrum, ui.player);

  // Judul lagu terbaru dan ikon aplikasi untuk kartu screensaver Google TV, Google Home, dan
  // kontrol Cast di HP lain.
  const withNowPlaying = (metadata) => {
    const music =
      metadata && metadata.metadataType === messages.MetadataType.MUSIC_TRACK
        ? metadata
        : new messages.MusicTrackMediaMetadata();
    music.title = current.title;
    music.artist = current.artist;
    const icon = new messages.Image(ICON.url);
    icon.width = ICON.size;
    icon.height = ICON.size;
    music.images = [icon];
    return music;
  };

  // Apa pun yang dikirim HP, siaran diperlakukan sebagai live (tanpa seek bar).
  playerManager.setMessageInterceptor(messages.MessageType.LOAD, async (request) => {
    request.media.streamType = messages.StreamType.LIVE;
    request.media.contentType = request.media.contentType || 'audio/mpeg';
    // Sebelum CAF memasang src stream: spektrum butuh crossOrigin, yang hanya boleh dipasang
    // kalau server mengizinkan.
    await spectrum.prepare(request.media.contentUrl || request.media.contentId);
    request.media.metadata = withNowPlaying(request.media.metadata);
    return request;
  });
  playerManager.addEventListener(events.EventType.PLAYING, () => spectrum.resume());

  const playbackLabels = {};
  playbackLabels[messages.PlayerState.BUFFERING] = 'Buffering…';
  playbackLabels[messages.PlayerState.PAUSED] = 'Dijeda';
  const updatePlayback = () => {
    const state = playerManager.getPlayerState();
    renderPlayback(playbackLabels[state] || '', state === messages.PlayerState.BUFFERING);
  };
  [
    events.EventType.PLAYER_LOAD_COMPLETE,
    events.EventType.PLAYING,
    events.EventType.PAUSE,
    events.EventType.BUFFERING,
    events.EventType.ENDED,
  ].forEach((type) => playerManager.addEventListener(type, updatePlayback));
  playerManager.addEventListener(events.EventType.ERROR, () =>
    renderPlayback('Siaran tidak dapat diputar'),
  );

  // Judul baru diteruskan ke semua yang menampilkan metadata media (lihat withNowPlaying).
  const broadcastNowPlaying = () => {
    const info = playerManager.getMediaInformation();
    if (!info) return;
    info.metadata = withNowPlaying(info.metadata);
    playerManager.setMediaInformation(info, /* broadcast= */ true);
  };
  startPolling(broadcastNowPlaying);
  // Poll bisa selesai saat LOAD masih diproses (belum ada media information). Tanpa ini judul
  // bawaan bertahan sampai lagu berganti, yang untuk khotbah bisa lebih dari sejam.
  playerManager.addEventListener(events.EventType.PLAYER_LOAD_COMPLETE, broadcastNowPlaying);

  context.start({ statusText: STATION.title });
}

if (new URLSearchParams(location.search).has('preview')) {
  // Di browser biasa: tampilkan UI dengan data live, tanpa framework Cast. Browser hanya boleh
  // memutar suara setelah ada klik, jadi stream (dan spektrumnya) baru jalan setelah diklik.
  const spectrum = createSpectrum(ui.spectrum, ui.player);
  renderPlayback('Pratinjau: klik untuk memutar');
  startPolling(() => {});
  document.addEventListener('click', async () => {
    await spectrum.prepare(PREVIEW_STREAM_URL);
    ui.player.src = PREVIEW_STREAM_URL;
    await ui.player.play();
    renderPlayback('Pratinjau');
  }, { once: true });
} else {
  startReceiver();
}
