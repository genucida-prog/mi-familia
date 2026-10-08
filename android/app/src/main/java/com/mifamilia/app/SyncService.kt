package com.mifamilia.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps Mi Familia alive while the WebView is closed.
 *
 * The service mirrors the page's MQTT contract exactly: same `nexo-familiar`
 * prefix, same topics (pos/evt/places/profile) and same payload shapes, so a
 * device running only the service is indistinguishable from one running the
 * page. It publishes this device's position, adopts newer shared places,
 * raises native notifications for remote arrivals/departures/SOS, and rings
 * the super-call alarm in a loop (audio at alarm volume + vibration + wake
 * lock) until someone actively stops it — never on a swipe or timeout.
 *
 * Everything is driven by the page through `window.NexoNative` (see
 * [MainActivity.NexoBridge]): `configure` starts/stops the whole pipeline,
 * `setPlaces` keeps the geofences in sync, `ringSuper`/`stopSuper` expose the
 * alarm. Config lives in SharedPreferences so a sticky restart reconnects on
 * its own.
 */
class SyncService : Service() {

    private lateinit var prefs: SharedPreferences
    private var workThread: HandlerThread? = null
    private var work: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var client: MqttClient? = null
    private var brokerIdx = 0
    private var configured = false

    private var room = ""
    private var identity = ""
    private var name = ""
    private var avatar = ""
    private var device = ""
    private var superOn = true
    private var notifLlegadas = true
    private var notifSos = true

    private var places = JSONArray()
    private var placesTs = 0L
    private var seenRemotePlacesTs = 0L
    private val fences = HashMap<String, Boolean>()
    private val seenEvt = LinkedHashSet<String>()
    private var remSeenTs = 0L
    private val scheduledRem = HashMap<String, PendingIntent>()
    private val remDue = HashMap<String, Long>()

    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var lastPosPublish = 0L
    private var notifSeq = 100

    private var locationManager: LocationManager? = null
    private var locationUpdates = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var savedVolume = -1
    private var ringing = false
    private var vibrator: Vibrator? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLat = location.latitude
            lastLng = location.longitude
            work?.post {
                publishPos(force = false)
                checkFences()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        room = prefs.getString(KEY_ROOM, "") ?: ""
        identity = prefs.getString(KEY_IDENTITY, "") ?: ""
        name = prefs.getString(KEY_NAME, "") ?: ""
        avatar = prefs.getString(KEY_AVATAR, "") ?: ""
        device = prefs.getString(KEY_DEVICE, "") ?: newDeviceId()
        superOn = prefs.getBoolean(KEY_SUPER_ON, true)
        notifLlegadas = prefs.getBoolean(KEY_NOTIF_LLEGADAS, true)
        notifSos = prefs.getBoolean(KEY_NOTIF_SOS, true)
        places = try { JSONArray(prefs.getString(KEY_PLACES, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        placesTs = prefs.getLong(KEY_PLACES_TS, 0L)
        remSeenTs = prefs.getLong(KEY_REM_TS, 0L)
        try {
            val savedRem = JSONObject(prefs.getString(KEY_REM, "{}") ?: "{}")
            for (k in savedRem.keys()) {
                val due = savedRem.optLong(k, 0)
                if (due > 0) {
                    remDue[k] = due
                    scheduledRem[k] = reminderPending(k, "", "")
                }
            }
        } catch (e: Exception) { /* sin recordatorios */ }
        configured = room.isNotEmpty()
        if (configured) active = true
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        workThread = HandlerThread("nexo-sync").also { it.start() }
        work = Handler(workThread!!.looper)
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONFIGURE -> {
                if (!startAsForeground("Conectando con la familia…")) return START_NOT_STICKY
                val cfg = try { JSONObject(intent.getStringExtra(EXTRA_JSON) ?: "{}") } catch (e: Exception) { JSONObject() }
                if (cfg.optString("room").isEmpty()) {
                    stopAll()
                    return START_NOT_STICKY
                }
                applyConfig(cfg)
            }
            ACTION_SET_PLACES -> {
                if (!configured) { stopSelf(startId); return START_NOT_STICKY }
                applyPlaces(intent.getStringExtra(EXTRA_JSON) ?: "{}")
            }
            ACTION_RING_SUPER -> {
                if (!configured) { stopSelf(startId); return START_NOT_STICKY }
                if (superOn) ringSuper()
            }
            ACTION_STOP_SUPER -> stopSuper()
            else -> {
                // Sticky restart: prefs already carry the last configuration.
                if (!configured) { stopSelf(startId); return START_NOT_STICKY }
                if (!startAsForeground("Sincronizando con la familia")) return START_NOT_STICKY
                registerLocation()
                work?.post { connectMqtt() }
            }
        }
        return if (configured) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        configured = false
        stopSuper()
        unregisterLocation()
        disconnectMqtt()
        workThread?.quitSafely()
        workThread = null
        work = null
        active = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===== Configuration =====

    private fun applyConfig(cfg: JSONObject) {
        room = cfg.optString("room", room)
        identity = cfg.optString("identity", identity)
        name = cfg.optString("name", name)
        avatar = cfg.optString("avatar", avatar)
        device = cfg.optString("device", device).ifEmpty { device }
        superOn = cfg.optBoolean("superOn", superOn)
        notifLlegadas = cfg.optBoolean("notifLlegadas", notifLlegadas)
        notifSos = cfg.optBoolean("notifSos", notifSos)
        if (cfg.has("places")) {
            val incoming = cfg.optJSONArray("places")
            val incomingTs = cfg.optLong("placesTs", placesTs)
            if (incoming != null && incomingTs >= placesTs) {
                places = incoming
                placesTs = incomingTs
                fences.clear()
            }
        }
        prefs.edit()
            .putString(KEY_ROOM, room)
            .putString(KEY_IDENTITY, identity)
            .putString(KEY_NAME, name)
            .putString(KEY_AVATAR, avatar)
            .putString(KEY_DEVICE, device)
            .putBoolean(KEY_SUPER_ON, superOn)
            .putBoolean(KEY_NOTIF_LLEGADAS, notifLlegadas)
            .putBoolean(KEY_NOTIF_SOS, notifSos)
            .putString(KEY_PLACES, places.toString())
            .putLong(KEY_PLACES_TS, placesTs)
            .apply()
        configured = true
        active = true
        registerLocation()
        work?.post {
            connectMqtt()
            checkFences()
        }
        updateOngoing("Sala $room · sincronizando")
    }

    private fun applyPlaces(raw: String) {
        val data = try { JSONObject(raw) } catch (e: Exception) { return }
        val incoming = data.optJSONArray("places") ?: return
        val incomingTs = data.optLong("ts", System.currentTimeMillis())
        if (incomingTs < placesTs) return
        places = incoming
        placesTs = incomingTs
        fences.clear()
        prefs.edit().putString(KEY_PLACES, places.toString()).putLong(KEY_PLACES_TS, placesTs).apply()
        work?.post { checkFences() }
    }

    private fun stopAll() {
        configured = false
        active = false
        stopSuper()
        unregisterLocation()
        disconnectMqtt()
        prefs.edit().remove(KEY_ROOM).apply()
        stopForeground(true)
        stopSelf()
    }

    private fun newDeviceId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val id = "n" + buildString {
            repeat(8) { append(alphabet[(Math.random() * alphabet.length).toInt()]) }
        }
        prefs.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    // ===== MQTT (same contract as the web client) =====

    private fun connectMqtt() {
        if (!configured) return
        disconnectMqtt()
        if (brokerIdx >= BROKERS.size) brokerIdx = 0
        val url = BROKERS[brokerIdx]
        try {
            val c = MqttClient(url, "nexo-$device-${System.currentTimeMillis().toString(36)}", MemoryPersistence())
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    onMqttConnected()
                }

                override fun connectionLost(cause: Throwable?) {
                    updateOngoing("Sin conexión · reintentando…")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val t = topic ?: return
                    val payload = message?.payload ?: return
                    val raw = String(payload, Charsets.UTF_8)
                    work?.post { onMessage(t, raw) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10_000
                keepAliveInterval = 30
            }
            client = c
            c.connect(opts)
        } catch (e: Exception) {
            client = null
            brokerIdx += 1
            work?.postDelayed({ connectMqtt() }, 5000)
        }
    }

    private fun onMqttConnected() {
        val c = client ?: return
        val base = "$PREFIX/$room"
        try {
            c.subscribe("$base/evt", 1)
            c.subscribe("$base/places", 1)
            c.subscribe("$base/chat", 1)
            c.subscribe("$base/rem", 1)
        } catch (e: Exception) { /* sin conexión */ }
        publishProfile()
        publishPos(force = true)
        updateOngoing("Sala $room · sincronizado")
        work?.postDelayed({
            if (configured && placesTs > seenRemotePlacesTs) publishPlaces()
        }, 2000)
    }

    private fun disconnectMqtt() {
        val c = client ?: return
        client = null
        try { c.disconnect(200) } catch (e: Exception) { /* ya cerrado */ }
        try { c.close() } catch (e: Exception) { /* ya cerrado */ }
    }

    private fun publish(topic: String, payload: JSONObject, retain: Boolean) {
        try {
            client?.publish(topic, payload.toString().toByteArray(Charsets.UTF_8), 1, retain)
        } catch (e: Exception) { /* sin conexión */ }
    }

    private fun publishPos(force: Boolean) {
        val c = client
        if (!configured || c == null || !c.isConnected || lastLat.isNaN()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPosPublish < 15_000) return
        lastPosPublish = now
        val payload = JSONObject()
            .put("v", 1)
            .put("device", device)
            .put("id", identity)
            .put("name", name)
            .put("initial", if (name.isBlank()) "?" else name.trim().take(1).uppercase())
            .put("lat", lastLat)
            .put("lng", lastLng)
            .put("batt", batteryPct() ?: JSONObject.NULL)
            .put("ts", now)
        publish("$PREFIX/$room/pos/$identity", payload, retain = true)
    }

    private fun publishProfile() {
        val payload = JSONObject()
            .put("v", 1)
            .put("device", device)
            .put("id", identity)
            .put("name", name)
            .put("avatar", avatar)
            .put("ts", System.currentTimeMillis())
        publish("$PREFIX/$room/profile/$identity", payload, retain = true)
    }

    private fun publishPlaces() {
        val payload = JSONObject()
            .put("v", 1)
            .put("device", device)
            .put("ts", placesTs)
            .put("places", places)
        publish("$PREFIX/$room/places", payload, retain = true)
    }

    private fun publishEvt(kind: String, place: String) {
        val payload = JSONObject()
            .put("v", 1)
            .put("device", device)
            .put("id", identity)
            .put("name", name)
            .put("kind", kind)
            .put("place", place)
            .put("to", JSONObject.NULL)
            .put("lat", if (lastLat.isNaN()) JSONObject.NULL else lastLat)
            .put("lng", if (lastLng.isNaN()) JSONObject.NULL else lastLng)
            .put("ts", System.currentTimeMillis())
        publish("$PREFIX/$room/evt", payload, retain = false)
    }

    private fun onMessage(topic: String, raw: String) {
        if (!configured) return
        val data = try { JSONObject(raw) } catch (e: Exception) { return }
        if (data.optString("device") == device) return
        when {
            topic.endsWith("/places") -> {
                val ts = data.optLong("ts", 0)
                if (ts > seenRemotePlacesTs) seenRemotePlacesTs = ts
                if (ts <= placesTs) return
                val incoming = data.optJSONArray("places") ?: return
                places = incoming
                placesTs = ts
                fences.clear()
                prefs.edit().putString(KEY_PLACES, incoming.toString()).putLong(KEY_PLACES_TS, ts).apply()
                checkFences()
            }
            topic.endsWith("/chat") -> handleChat(data)
            topic.endsWith("/rem") -> handleRem(data)
            topic.endsWith("/evt") -> handleEvt(data)
        }
    }

    private fun handleChat(data: JSONObject) {
        val from = data.optString("name")
        val text = data.optString("text").replace("<", "").replace(">", "").trim().take(240)
        if (from.isEmpty() || text.isEmpty()) return
        val bucket = data.optLong("ts", System.currentTimeMillis()) / 60000
        val key = "${data.optString("id")}|chat|$bucket"
        if (!seenEvt.add(key)) return
        if (seenEvt.size > 500) seenEvt.clear()
        notifyUser("Mensaje de $from", text)
    }

    private fun handleRem(data: JSONObject) {
        val ts = data.optLong("ts", 0)
        if (ts < remSeenTs) return
        remSeenTs = ts
        val arr = data.optJSONArray("reminders") ?: JSONArray()
        scheduleReminders(arr)
    }

    private fun scheduleReminders(arr: JSONArray) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val active = HashSet<String>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optString("id")
            val text = r.optString("text").replace("<", "").replace(">", "").trim().take(240)
            val due = r.optLong("due", 0)
            if (id.isEmpty() || text.isEmpty() || due <= now) continue
            active.add(id)
            if (remDue[id] == due && scheduledRem.containsKey(id)) continue
            val existing = scheduledRem.remove(id)
            if (existing != null) {
                try { am.cancel(existing) } catch (e: Exception) { /* sin alarma */ }
                existing.cancel()
            }
            val pi = reminderPending(id, text, r.optString("ownerName"))
            scheduledRem[id] = pi
            remDue[id] = due
            try {
                when {
                    Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms() ->
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
                    Build.VERSION.SDK_INT >= 23 ->
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
                    else -> am.set(AlarmManager.RTC_WAKEUP, due, pi)
                }
            } catch (e: Exception) {
                try { am.set(AlarmManager.RTC_WAKEUP, due, pi) } catch (e2: Exception) { /* sin alarma */ }
            }
        }
        val stale = scheduledRem.keys.filter { it !in active }
        for (id in stale) {
            val pi = scheduledRem.remove(id)
            remDue.remove(id)
            if (pi != null) {
                try { am.cancel(pi) } catch (e: Exception) { /* sin alarma */ }
                pi.cancel()
            }
        }
        persistRemState()
    }

    private fun reminderPending(id: String, text: String, from: String): PendingIntent {
        val intent = Intent(this, ReminderReceiver::class.java)
            .putExtra("id", id)
            .putExtra("text", text)
            .putExtra("from", from)
        return PendingIntent.getBroadcast(
            this, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun persistRemState() {
        val obj = JSONObject()
        for ((id, due) in remDue) obj.put(id, due)
        prefs.edit().putString(KEY_REM, obj.toString()).putLong(KEY_REM_TS, remSeenTs).apply()
    }

    private fun handleEvt(data: JSONObject) {
        val kind = data.optString("kind")
        val from = data.optString("name")
        if (kind.isEmpty() || from.isEmpty()) return
        val place = data.optString("place")
        val bucket = data.optLong("ts", System.currentTimeMillis()) / 60000
        val key = "${data.optString("id")}|$kind|$place|$bucket"
        if (!seenEvt.add(key)) return
        if (seenEvt.size > 500) seenEvt.clear()
        when (kind) {
            "super" -> {
                val to = if (data.isNull("to")) "" else data.optString("to")
                if (to.isNotEmpty() && to != identity) return
                notifyUser("Superllamada de $from", "Abre Mi Familia para parar la alarma.")
                if (superOn) ringSuper()
            }
            "sos" -> {
                if (!notifSos) return
                notifyUser("SOS de $from", "Su ubicación está en vivo en el mapa. Si es una emergencia real, avisa al 112.")
                if (superOn) ringSuper()
            }
            "llegada", "salida" -> {
                if (!notifLlegadas) return
                val text = if (kind == "llegada") "$from llegó a $place" else "$from salió de $place"
                notifyUser("Mi Familia", text)
            }
        }
    }

    // ===== Location + geofences =====

    private fun registerLocation() {
        if (locationUpdates) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                if (lastLat.isNaN()) { lastLat = it.latitude; lastLng = it.longitude }
            }
        } catch (e: Exception) { /* sin fix */ }
        try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                if (lastLat.isNaN()) { lastLat = it.latitude; lastLng = it.longitude }
            }
        } catch (e: Exception) { /* sin fix */ }
        val looper = Looper.getMainLooper()
        if (fine) {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15_000L, 10f, locationListener, looper) } catch (e: Exception) { /* sin GPS */ }
        }
        try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 20f, locationListener, looper) } catch (e: Exception) { /* sin red */ }
        locationUpdates = true
    }

    private fun unregisterLocation() {
        val lm = locationManager ?: return
        try { lm.removeUpdates(locationListener) } catch (e: Exception) { /* sin registro */ }
        locationUpdates = false
    }

    private fun checkFences() {
        if (lastLat.isNaN() || identity.isEmpty()) return
        for (i in 0 until places.length()) {
            val p = places.optJSONObject(i) ?: continue
            if (!p.optBoolean("alerts", true)) continue
            val plat = p.optDouble("lat", Double.NaN)
            val plng = p.optDouble("lng", Double.NaN)
            if (plat.isNaN() || plng.isNaN()) continue
            val radius = p.optInt("radius", 200)
            val inside = haversine(lastLat, lastLng, plat, plng) * 1000.0 <= radius
            val id = p.optString("id")
            val prev = fences[id]
            fences[id] = inside
            if (prev == null || prev == inside) continue
            val place = p.optString("name")
            val kind = if (inside) "llegada" else "salida"
            val bucket = System.currentTimeMillis() / 60000
            if (!seenEvt.add("$id|$kind|$place|$bucket")) continue
            publishEvt(kind, place)
            if (notifLlegadas) {
                notifyUser("Mi Familia", if (inside) "$name llegó a $place" else "$name salió de $place")
            }
        }
    }

    private fun haversine(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(s), sqrt(1 - s))
    }

    private fun batteryPct(): Int? {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 1..100) pct else null
        } catch (e: Exception) { null }
    }

    // ===== Notifications =====

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Sincronización", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_AVISOS, "Avisos de la familia", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARMA, "Alarma", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true); setSound(null, null) }
        )
    }

    private fun notifyUser(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels()
        val n = NotificationCompat.Builder(this, CH_AVISOS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifSeq++, n)
    }

    private fun ongoingNotification(text: String): Notification {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_SYNC)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Mi Familia · sala $room")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateOngoing(text: String) {
        if (!configured) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ONGOING, ongoingNotification(text))
        } catch (e: Exception) { /* sin permiso */ }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun startAsForeground(text: String): Boolean {
        val n = ongoingNotification(text)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ONGOING, n)
            }
            true
        } catch (e: Exception) {
            // No location permission yet: the OS refuses a location-typed
            // foreground service. The page keeps syncing while it is open.
            stopSelf()
            false
        }
    }

    // ===== Super-call alarm: loops until someone actively stops it =====

    private fun alarmNotification(): Notification {
        ensureChannels()
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SyncService::class.java).setAction(ACTION_STOP_SUPER),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ALARMA)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Superllamada")
            .setContentText("La alarma suena hasta que la pares")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(0, "Parar alarma", stop)
            .build()
    }

    private fun ringSuper() {
        if (ringing) return
        ringing = true
        ensureChannels()
        acquireWake()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (savedVolume < max) am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@SyncService, uri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) { /* sin audio: sigue la vibración */ }
        @Suppress("DEPRECATION")
        vibrator?.let { v ->
            val pattern = longArrayOf(0, 400, 200, 400, 200, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                v.vibrate(pattern, -1)
            }
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ALARM, alarmNotification())
        } catch (e: Exception) { /* sin permiso */ }
    }

    private fun stopSuper() {
        if (!ringing && mediaPlayer == null && wakeLock?.isHeld != true) return
        ringing = false
        try { mediaPlayer?.stop() } catch (e: Exception) { /* ya parado */ }
        try { mediaPlayer?.release() } catch (e: Exception) { /* ya liberado */ }
        mediaPlayer = null
        @Suppress("DEPRECATION")
        try { vibrator?.cancel() } catch (e: Exception) { /* sin vibración */ }
        if (savedVolume >= 0) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (e: Exception) { /* sin audio */ }
            savedVolume = -1
        }
        try { wakeLock?.release() } catch (e: Exception) { /* ya liberado */ }
        wakeLock = null
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ALARM)
        } catch (e: Exception) { /* sin permiso */ }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mifamilia:alarma").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        const val ACTION_CONFIGURE = "com.mifamilia.app.action.CONFIGURE"
        const val ACTION_SET_PLACES = "com.mifamilia.app.action.SET_PLACES"
        const val ACTION_RING_SUPER = "com.mifamilia.app.action.RING_SUPER"
        const val ACTION_STOP_SUPER = "com.mifamilia.app.action.STOP_SUPER"
        const val EXTRA_JSON = "json"

        const val PREFIX = "nexo-familiar"
        val BROKERS = listOf("tcp://broker.emqx.io:1883", "tcp://broker.hivemq.com:1883")

        const val PREFS = "mifamilia_sync"
        const val KEY_ROOM = "room"
        const val KEY_IDENTITY = "identity"
        const val KEY_NAME = "name"
        const val KEY_AVATAR = "avatar"
        const val KEY_DEVICE = "device"
        const val KEY_SUPER_ON = "superOn"
        const val KEY_NOTIF_LLEGADAS = "notifLlegadas"
        const val KEY_NOTIF_SOS = "notifSos"
        const val KEY_PLACES = "places"
        const val KEY_PLACES_TS = "placesTs"
        const val KEY_REM = "remScheduled"
        const val KEY_REM_TS = "remSeenTs"

        const val CH_SYNC = "nexo_sync"
        const val CH_AVISOS = "nexo_avisos"
        const val CH_ALARMA = "nexo_alarma"
        const val NOTIF_ONGOING = 10
        const val NOTIF_ALARM = 11

        @Volatile
        var active = false
    }
}
