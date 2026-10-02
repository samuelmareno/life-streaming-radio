// Jalankan: node --test docs/cast-receiver/screen.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createScreenKeeper } from './screen.js';

function fakeSentinel() {
  const onRelease = [];
  return {
    released: false,
    release() {
      this.released = true;
      onRelease.forEach((listener) => listener());
      return Promise.resolve();
    },
    addEventListener(type, listener) {
      if (type === 'release') onRelease.push(listener);
    },
  };
}

/** Wake Lock palsu: tiap request menunggu sampai tes memberinya sentinel. */
function fakeTv({ supported = true } = {}) {
  const listeners = [];
  const requests = [];
  const doc = {
    visibilityState: 'visible',
    addEventListener(type, listener) {
      if (type === 'visibilitychange') listeners.push(listener);
    },
    setVisibility(state) {
      this.visibilityState = state;
      listeners.forEach((listener) => listener());
    },
  };
  const nav = supported
    ? {
        wakeLock: {
          request: () => new Promise((resolve) => requests.push(resolve)),
        },
      }
    : {};
  return { doc, nav, requests };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

async function grant(tv, index = tv.requests.length - 1) {
  const sentinel = fakeSentinel();
  tv.requests[index](sentinel);
  await settle();
  return sentinel;
}

test('satu kunci walau keepOn dipanggil berkali-kali', async () => {
  const tv = fakeTv();
  const keeper = createScreenKeeper({ nav: tv.nav, doc: tv.doc });
  keeper.keepOn();
  keeper.keepOn();
  await grant(tv);
  keeper.keepOn();
  assert.equal(tv.requests.length, 1);
});

test('allowOff melepas kunci', async () => {
  const tv = fakeTv();
  const keeper = createScreenKeeper({ nav: tv.nav, doc: tv.doc });
  keeper.keepOn();
  const sentinel = await grant(tv);
  keeper.allowOff();
  assert.equal(sentinel.released, true);
});

test('kunci yang baru datang setelah allowOff langsung dilepas', async () => {
  const tv = fakeTv();
  const keeper = createScreenKeeper({ nav: tv.nav, doc: tv.doc });
  keeper.keepOn();
  keeper.allowOff();
  const sentinel = await grant(tv);
  assert.equal(sentinel.released, true);
});

test('diminta lagi setelah sistem melepasnya dan halaman tampil kembali', async () => {
  const tv = fakeTv();
  const keeper = createScreenKeeper({ nav: tv.nav, doc: tv.doc });
  keeper.keepOn();
  const sentinel = await grant(tv);
  tv.doc.setVisibility('hidden');
  sentinel.release(); // seperti yang dilakukan browser saat halaman tersembunyi
  tv.doc.setVisibility('visible');
  assert.equal(tv.requests.length, 2);
});

test('tidak meminta kunci saat halaman tersembunyi atau radio dijeda', async () => {
  const tv = fakeTv();
  const keeper = createScreenKeeper({ nav: tv.nav, doc: tv.doc });
  tv.doc.setVisibility('hidden');
  keeper.keepOn();
  assert.equal(tv.requests.length, 0);
  keeper.allowOff();
  tv.doc.setVisibility('visible');
  assert.equal(tv.requests.length, 0);
});

test('TV tanpa Wake Lock API: tidak error, tidak terjadi apa-apa', () => {
  const tv = fakeTv({ supported: false });
  const keeper = createScreenKeeper({ nav: tv.nav, doc: tv.doc });
  assert.doesNotThrow(() => {
    keeper.keepOn();
    keeper.allowOff();
  });
});
