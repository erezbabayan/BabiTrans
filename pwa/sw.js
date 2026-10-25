const SHELL = 'matamel-shell-v3';
const SHARE = 'matamel-share';
const ASSETS = ['/', '/index.html', '/app.js', '/style.css', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', e => { e.waitUntil(caches.open(SHELL).then(c => c.addAll(ASSETS))); self.skipWaiting(); });
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method === 'POST' && url.pathname === '/share-target') {
    e.respondWith((async () => {
      const form = await e.request.formData();
      const file = form.get('audio');
      const cache = await caches.open(SHARE);
      if (file && typeof file !== 'string') {
        await cache.put('/shared-audio', new Response(file, { headers: {
          'Content-Type': file.type || 'audio/ogg',
          'X-Filename': encodeURIComponent(file.name || 'voice.ogg') } }));
      }
      return Response.redirect('/?share=1', 303);
    })());
    return;
  }
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (['/health', '/transcribe'].includes(url.pathname)) return;
  // network-first for shell so updates land; cache fallback offline
  e.respondWith(fetch(e.request).then(r => {
    const copy = r.clone(); caches.open(SHELL).then(c => c.put(e.request, copy)); return r;
  }).catch(() => caches.match(e.request, { ignoreSearch: true })));
});
