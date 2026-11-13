package com.aile.mesaj

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.UUID

/** Central state: settings, local messages, and routing between Bluetooth and internet. */
object Hub {
    private var appCtx: Context? = null
    lateinit var db: Db
    private lateinit var prefs: SharedPreferences
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val config = MutableStateFlow<FamilyConfig?>(null)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val members = MutableStateFlow<List<Member>>(emptyList())
    val btPeers = MutableStateFlow<Set<String>>(emptySet())
    val netStatus = MutableStateFlow("Kapalı")
    val btStatus = MutableStateFlow("Kapalı")
    val retention = MutableStateFlow(Retention.WEEK)
    val keepRunning = MutableStateFlow(true)

    @Volatile var uiVisible = false
    @Volatile var serviceRunning = false
    var myId = ""; private set
    var myName = ""; private set

    @Synchronized
    fun init(c: Context) {
        if (appCtx != null) return
        val app = c.applicationContext
        appCtx = app
        db = Db(app)
        prefs = app.getSharedPreferences("aile", Context.MODE_PRIVATE)
        myId = prefs.getString("me_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("me_id", it).apply() }
        myName = prefs.getString("me_name", "") ?: ""
        retention.value = runCatching { Retention.valueOf(prefs.getString("retention", "WEEK")!!) }
            .getOrDefault(Retention.WEEK)
        keepRunning.value = prefs.getBoolean("keep", true)
        config.value = prefs.getString("family", null)?.let {
            runCatching { FamilyConfig.fromJson(JSONObject(String(Crypto.localDecrypt(Crypto.unb64(it))))) }.getOrNull()
        }
        cleanup()
        refresh()
    }

    fun setMyName(n: String) {
        myName = n
        prefs.edit().putString("me_name", n).apply()
    }

    fun applyConfig(c: Context, cfg: FamilyConfig) {
        prefs.edit().putString("family", Crypto.b64(Crypto.localEncrypt(cfg.toJson().toString().toByteArray()))).apply()
        config.value = cfg
        if (serviceRunning) restartTransports() else startService(c)
    }

    fun setRetention(r: Retention) {
        prefs.edit().putString("retention", r.name).apply()
        retention.value = r
        cleanup()
    }

    fun setKeepRunning(b: Boolean) {
        prefs.edit().putBoolean("keep", b).apply()
        keepRunning.value = b
    }

    fun refresh() {
        messages.value = db.messages()
        members.value = db.members().filter { it.id != myId }
    }

    /** Deletes messages older than the chosen period. Called hourly, on open and every few minutes. */
    fun cleanup() {
        val r = retention.value
        if (r.ms > 0 && db.deleteOlderThan(System.currentTimeMillis() - r.ms) > 0) refresh()
    }

    /** Blocking; call from a background thread. */
    fun send(text: String) {
        if (config.value == null) return
        val t = text.trim()
        if (t.isEmpty()) return
        val m = Msg(UUID.randomUUID().toString(), myId, myName, t, System.currentTimeMillis(), "sending")
        db.insertMsg(m)
        refresh()
        val payload = JSONObject().put("t", "msg").put("id", m.id).put("from", myId)
            .put("name", myName).put("text", t).put("ts", m.ts).toString()
        val routes = sortedSetOf<String>()
        for (mem in db.members()) {
            if (mem.id == myId) continue
            when {
                Bt.sendTo(mem.id, payload) -> routes += "bt"
                Net.sendTo(mem.id, payload) -> routes += "net"
                else -> routes += "none"
            }
        }
        val via = when {
            routes.isEmpty() || routes == setOf("none") -> "none"
            else -> routes.filter { it != "none" }.joinToString("+")
        }
        db.updateVia(m.id, via)
        refresh()
    }

    fun receive(json: String, via: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (o.optString("t") != "msg") return
        val from = o.optString("from")
        if (from.isBlank() || from == myId) return
        val name = o.optString("name")
        onMember(from, name)
        val ts = o.optLong("ts", System.currentTimeMillis())
        val r = retention.value
        if (r.ms > 0 && ts < System.currentTimeMillis() - r.ms) return // already past the deletion period
        val m = Msg(o.optString("id", UUID.randomUUID().toString()), from, name, o.optString("text"), ts, via)
        if (db.insertMsg(m)) {
            refresh()
            if (!uiVisible) appCtx?.let { Notifier.message(it, m) }
        }
    }

    fun onMember(id: String, name: String) {
        if (id.isBlank() || id == myId) return
        val existing = db.members().firstOrNull { it.id == id }
        if (existing != null && existing.name == name) return
        db.upsertMember(Member(id, name, System.currentTimeMillis()))
        members.value = db.members().filter { it.id != myId }
    }

    fun startTransports(c: Context) {
        val cfg = config.value ?: return
        Net.start(c.applicationContext, cfg, myId, myName)
        Bt.start(c.applicationContext, cfg, myId, myName)
    }

    fun stopTransports() {
        Net.stop()
        Bt.stop()
    }

    fun restartTransports() {
        val c = appCtx ?: return
        if (!serviceRunning) return
        stopTransports()
        startTransports(c)
    }

    fun startService(c: Context) {
        if (config.value == null) return
        runCatching { ContextCompat.startForegroundService(c, Intent(c, MessagingService::class.java)) }
    }

    fun stopService(c: Context) {
        c.stopService(Intent(c, MessagingService::class.java))
    }

    fun deleteAllMessages() {
        db.deleteAllMessages()
        refresh()
    }

    /** Leave the family: removes the family key, members and all messages from this phone. */
    fun leave(c: Context) {
        Net.leave(myId)
        stopService(c)
        stopTransports()
        db.wipe()
        prefs.edit().remove("family").apply()
        config.value = null
        refresh()
    }
}
