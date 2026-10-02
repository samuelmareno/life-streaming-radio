// Jalankan: node --test docs/cast-receiver/spectrum.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bandLevel, bandRanges } from './spectrum.js';

function assertValidRanges(ranges, binCount, { contiguous = true } = {}) {
  ranges.forEach(([start, end], i) => {
    assert.ok(start >= 1, `band ${i} tidak boleh memakai bin 0 (DC)`);
    assert.ok(end > start, `band ${i} tidak boleh kosong`);
    assert.ok(end <= binCount, `band ${i} melewati jumlah bin`);
    if (i === 0) return;
    if (contiguous) {
      assert.equal(start, ranges[i - 1][1], `band ${i} harus menyambung band sebelumnya`);
    } else {
      assert.ok(start >= ranges[i - 1][0], `band ${i} tidak boleh mundur`);
    }
  });
}

test('band logaritmik pada FFT 2048 di 48 kHz', () => {
  const ranges = bandRanges(1024, 48000);
  assert.equal(ranges.length, 18);
  assertValidRanges(ranges, 1024);
  const hzPerBin = 48000 / 2 / 1024;
  assert.ok(Math.abs(ranges[0][0] * hzPerBin - 60) < hzPerBin, 'band pertama mulai sekitar 60 Hz');
  assert.ok(Math.abs(ranges[17][1] * hzPerBin - 10000) < hzPerBin, 'band terakhir berakhir sekitar 10 kHz');
  // Band rendah sempit, band tinggi lebar (seperti pendengaran).
  assert.ok(ranges[17][1] - ranges[17][0] > ranges[0][1] - ranges[0][0]);
});

test('tetap valid di 44,1 kHz', () => {
  assertValidRanges(bandRanges(1024, 44100), 1024);
});

test('batas atas di atas Nyquist: band teratas memakai bin tertinggi, tidak kosong', () => {
  assertValidRanges(bandRanges(64, 8000), 64, { contiguous: false });
});

test('sunyi = 0, penuh = 1', () => {
  assert.equal(bandLevel(new Uint8Array(1024), [10, 20], 0), 0);
  assert.equal(bandLevel(new Uint8Array(1024).fill(255), [10, 20], 0), 1);
});

test('band tinggi sedikit dinaikkan, tapi tidak lewat 1', () => {
  const data = new Uint8Array(1024).fill(100);
  assert.ok(bandLevel(data, [500, 600], 17) > bandLevel(data, [10, 20], 0));
  assert.ok(bandLevel(new Uint8Array(1024).fill(250), [500, 600], 17) <= 1);
});
