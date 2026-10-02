// Jalankan: node --test docs/cast-receiver/track.test.mjs
// Kasus-kasus ini sama dengan TrackMetadataTest di Android, supaya TV dan HP selalu sepakat.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nowPlayingFrom, parseIcyTrack } from './track.js';

const STATION = { title: 'Life Streaming Radio', artist: 'House of Life' };

test('memecah format ICY biasa', () => {
  assert.deepEqual(parseIcyTrack('Adon - Kau Telah Memilihku'), { artist: 'Adon', title: 'Kau Telah Memilihku' });
});

test('judul yang mengandung tanda hubung tidak ikut terpotong', () => {
  assert.deepEqual(parseIcyTrack('Sari Simorangkir - Ku Mau Cinta Yesus - Live'),
    { artist: 'Sari Simorangkir', title: 'Ku Mau Cinta Yesus - Live' });
});

test('spasi berlebih dirapikan', () => {
  assert.deepEqual(parseIcyTrack('   Adon   -   Kau Telah Memilihku   '), { artist: 'Adon', title: 'Kau Telah Memilihku' });
});

test('tanpa pemisah dianggap bukan format ICY', () => {
  assert.equal(parseIcyTrack('Air Mata Bangsaku'), null);
});

test('tanda hubung tanpa spasi bukan pemisah', () => {
  assert.equal(parseIcyTrack('Non-Stop Praise'), null);
});

test('sisi kosong ditolak', () => {
  assert.equal(parseIcyTrack('Adon - '), null);
  assert.equal(parseIcyTrack(' - Kau Telah Memilihku'), null);
});

test('masukan kosong atau null ditolak', () => {
  assert.equal(parseIcyTrack(null), null);
  assert.equal(parseIcyTrack(undefined), null);
  assert.equal(parseIcyTrack(''), null);
  assert.equal(parseIcyTrack('    '), null);
});

test('tampilan: teks tanpa pemisah memakai nama stasiun sebagai artis', () => {
  assert.deepEqual(nowPlayingFrom('Air Mata Bangsaku', STATION), { title: 'Air Mata Bangsaku', artist: 'House of Life' });
});

test('tampilan: tanpa teks menampilkan nama stasiun', () => {
  assert.deepEqual(nowPlayingFrom(null, STATION), STATION);
});
