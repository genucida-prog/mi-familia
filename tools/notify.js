/* Avisos web push de Mi Familia (sin backend propio).
 *
 * Se ejecuta en GitHub Actions cada 5 minutos: escucha el broker público
 * (temas nexo-familiar/<sala>/chat y /evt durante ~100 s), recupera las
 * suscripciones retenidas de nexo-familiar/<sala>/push/sub/<identity> y
 * envía una notificación web por cada evento a la sala correspondiente,
 * excluyendo al emisor. La clave privada VAPID vive en los secrets del repo.
 */
const mqtt = require("mqtt");
const webpush = require("web-push");

const VAPID_PUBLIC_KEY =
  "BCmvbtwyPgRWbU41zCS02ZQIuPWi_AHdRX66DI-XKNsZAcmrhyxXOxRpOUfZt9sg0KRu9sPg4yT5UNyH82F7YrY";
const SUBJECT = process.env.VAPID_SUBJECT || "mailto:genucida-prog@users.noreply.github.com";
const BROKERS = ["wss://broker.emqx.io:8084/mqtt", "wss://broker.hivemq.com:8884/mqtt"];
const LISTEN_MS = 100000;

if (!process.env.VAPID_PRIVATE_KEY) {
  console.log("VAPID_PRIVATE_KEY ausente: nada que enviar.");
  process.exit(0);
}
webpush.setVapidDetails(SUBJECT, VAPID_PUBLIC_KEY, process.env.VAPID_PRIVATE_KEY);

const subs = {}; // "sala|identity" -> { endpoint, keys }
const events = []; // { room, from, title, body, url }

function onMessage(topic, payload) {
  const p = topic.split("/");
  if (p[0] !== "nexo-familiar" || p.length < 3) return;
  if (p[2] === "push" && p[3] === "sub" && p[4]) {
    const raw = payload.toString();
    if (!raw) {
      delete subs[p[1] + "|" + p[4]];
      return;
    }
    try {
      const j = JSON.parse(raw);
      if (j && j.endpoint && j.keys && j.keys.p256dh && j.keys.auth) {
        subs[p[1] + "|" + p[4]] = { endpoint: j.endpoint, keys: j.keys };
      }
    } catch (e) { /* payload inválido */ }
    return;
  }
  if (p[2] !== "chat" && p[2] !== "evt") return;
  let d;
  try {
    d = JSON.parse(payload.toString());
  } catch (e) {
    return;
  }
  if (!d || !d.name) return;
  const from = d.id || d.device || "";
  if (p[2] === "chat") {
    const text = String(d.text || "").replace(/[<>]/g, "").trim().slice(0, 240);
    if (!text) return;
    events.push({ room: p[1], from: from, title: "Mensaje de " + d.name, body: text, url: "./?target=chat" });
    return;
  }
  if (d.kind === "super") {
    events.push({ room: p[1], from: from, title: "Superllamada de " + d.name, body: "Abre Mi Familia para parar la alarma." });
  } else if (d.kind === "sos") {
    events.push({ room: p[1], from: from, title: "SOS de " + d.name, body: "Su ubicación está en vivo en el mapa. Si es una emergencia real, avisa al 112." });
  } else if (d.kind === "llegada" || d.kind === "salida") {
    events.push({
      room: p[1],
      from: from,
      title: "Mi Familia",
      body: d.name + (d.kind === "llegada" ? " llegó a " : " salió de ") + (d.place || "")
    });
  }
}

async function deliver() {
  console.log(events.length + " eventos · " + Object.keys(subs).length + " suscripciones");
  const sends = [];
  for (const ev of events) {
    for (const key of Object.keys(subs)) {
      const parts = key.split("|");
      if (parts[0] !== ev.room) continue;
      if (parts[1] === ev.from) continue;
      const sub = subs[key];
      sends.push(
        webpush
          .sendNotification(sub, JSON.stringify({ title: ev.title, body: ev.body, url: ev.url || "./" }))
          .catch(err => {
            const code = err && err.statusCode;
            if (code === 404 || code === 410) delete subs[key]; // suscripción caducada
            console.error("push error " + (code || (err && err.message) || "?"));
          })
      );
    }
  }
  await Promise.all(sends);
}

function run(brokerIdx) {
  const url = BROKERS[brokerIdx];
  if (!url) {
    console.log("Sin broker disponible: nada que enviar.");
    process.exit(0);
  }
  const client = mqtt.connect(url, { connectTimeout: 15000, reconnectPeriod: 3000 });
  let done = false;
  let connectedOnce = false;

  const finish = async () => {
    if (done) return;
    done = true;
    try {
      await deliver();
    } catch (e) {
      console.error("entrega fallida: " + e.message);
    }
    try { client.end(true); } catch (e) { /* ya cerrado */ }
    process.exit(0);
  };

  client.on("connect", () => {
    connectedOnce = true;
    client.subscribe("nexo-familiar/+/#", { qos: 1 }, err => {
      if (err) console.error("subscribe: " + err.message);
    });
  });
  client.on("message", onMessage);
  client.on("error", err => {
    console.error("broker: " + err.message);
  });
  client.on("close", () => {
    if (!connectedOnce && !done) {
      try { client.end(true); } catch (e) { /* ya cerrado */ }
      run(brokerIdx + 1);
    }
  });

  setTimeout(finish, LISTEN_MS);
}

run(0);
