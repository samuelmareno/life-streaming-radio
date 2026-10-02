import { nowPlayingFrom } from './track.js';

// Sama dengan aplikasi: NOWPLAYING_URL (Android, gradle.properties) & Constants.nowPlayingURL (iOS).
const NOWPLAYING_URL = 'https://a2.siar.us/api/nowplaying_static/life_radio.json';
const POLL_INTERVAL_MS = 15000;
const STATION = Object.freeze({ title: 'Life Streaming Radio', artist: 'House of Life' });
/** Gambar untuk notifikasi & lock screen HP; harus URL publik, bukan file di dalam aplikasi. */
const LOGO_URL = new URL('logo.png', location.href).href;

const ui = {
  status: document.getElementById('status'),
  title: document.getElementById('title'),
  artist: document.getElementById('artist'),
  playback: document.getElementById('playback'),
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

  playerManager.setMediaElement(document.getElementById('player'));

  const withNowPlaying = (metadata) => {
    const music =
      metadata && metadata.metadataType === messages.MetadataType.MUSIC_TRACK
        ? metadata
        : new messages.MusicTrackMediaMetadata();
    music.title = current.title;
    music.artist = current.artist;
    music.images = [new messages.Image(LOGO_URL)];
    return music;
  };

  // Apa pun yang dikirim HP, siaran diperlakukan sebagai live (tanpa seek bar) dan langsung
  // memakai judul terbaru serta logo.
  playerManager.setMessageInterceptor(messages.MessageType.LOAD, (request) => {
    request.media.streamType = messages.StreamType.LIVE;
    request.media.contentType = request.media.contentType || 'audio/mpeg';
    request.media.metadata = withNowPlaying(request.media.metadata);
    return request;
  });

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

  // Judul baru diteruskan ke semua HP yang terhubung, jadi notifikasi & lock screen ikut update.
  startPolling(() => {
    const info = playerManager.getMediaInformation();
    if (!info) return;
    info.metadata = withNowPlaying(info.metadata);
    playerManager.setMediaInformation(info, /* broadcast= */ true);
  });

  context.start({ statusText: STATION.title });
}

if (new URLSearchParams(location.search).has('preview')) {
  // Di browser biasa: tampilkan UI dengan data live, tanpa framework Cast.
  renderPlayback('Pratinjau');
  startPolling(() => {});
} else {
  startReceiver();
}
