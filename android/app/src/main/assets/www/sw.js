const CACHE = "mi-familia-v3";

const PRECACHE = [
  "index.html",
  "manifest.webmanifest",
  "icon.svg",
  "icon-1024.png",
  "icon-512.png",
  "icon-180.png",
  "favicon-32.png",
  "favicon-16.png",
  "vendor/leaflet/leaflet.css",
  "vendor/leaflet/leaflet.js",
  "vendor/mqtt/mqtt.min.js"
];

self.addEventListener("install", event => {
  event.waitUntil(
    caches.open(CACHE).then(cache =>
      Promise.all(
        PRECACHE.map(url =>
          cache.add(new Request(url, { cache: "reload" })).catch(() => {})
        )
      )
    ).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys()
      .then(keys =>
        Promise.all(
          keys.filter(key => key !== CACHE).map(key => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener("push", event => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { body: event.data ? event.data.text() : "" };
  }
  const title = data.title || "Mi Familia";
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || "",
      icon: "icon-512.png",
      badge: "favicon-32.png",
      data: { url: data.url || "./" }
    })
  );
});

self.addEventListener("notificationclick", event => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || "./";
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(list => {
      for (const client of list) {
        if ("focus" in client) return client.focus();
      }
      if (self.clients.openWindow) return self.clients.openWindow(url);
    })
  );
});

self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then(response => {
          const copy = response.clone();
          caches.open(CACHE).then(cache => cache.put("index.html", copy));
          return response;
        })
        .catch(() =>
          caches.match(request).then(cached => cached || caches.match("index.html"))
        )
    );
    return;
  }

  event.respondWith(
    caches.match(request).then(cached => {
      const network = fetch(request)
        .then(response => {
          if (response && response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then(cache => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached);
      return cached || network;
    })
  );
});
