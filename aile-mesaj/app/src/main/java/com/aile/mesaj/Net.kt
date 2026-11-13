package com.aile.mesaj

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import org.json.JSONObject
import java.util.Date

/**
 * Internet transport over Firestore. The server only ever sees encrypted blobs, and each
 * message is deleted from the server as soon as the recipient has received it.
 *
 *   families/{familyId}/members/{memberId}        -> encrypted {id, name}
 *   families/{familyId}/inbox/{memberId}/m/{auto} -> encrypted message, deleted on receipt
 */
object Net {
    private const val APP_NAME = "aile"
    private const val INBOX_TTL_MS = 7L * 24 * 3600_000L

    @Volatile private var fam: DocumentReference? = null
    private val regs = mutableListOf<ListenerRegistration>()
    private var key = ByteArray(0)
    @Volatile private var gen = 0

    @Synchronized
    fun start(c: Context, cfg: FamilyConfig, myId: String, myName: String) {
        val fb = cfg.fb ?: run { Hub.netStatus.value = "Firebase ayarlı değil"; return }
        key = cfg.key
        val g = ++gen
        Hub.netStatus.value = "Bağlanıyor…"
        val app = try {
            var a = FirebaseApp.getInstance(APP_NAME)
            if (a.options.projectId != fb.projectId || a.options.apiKey != fb.apiKey) {
                a.delete()
                a = FirebaseApp.initializeApp(c, options(fb), APP_NAME)
            }
            a
        } catch (e: IllegalStateException) {
            FirebaseApp.initializeApp(c, options(fb), APP_NAME)
        }
        val auth = FirebaseAuth.getInstance(app)
        val go = { if (g == gen) listen(app, cfg, myId, myName, g) }
        if (auth.currentUser != null) go()
        else auth.signInAnonymously()
            .addOnSuccessListener { go() }
            .addOnFailureListener { if (g == gen) Hub.netStatus.value = "Giriş hatası: ${it.message}" }
    }

    private fun options(fb: FbConfig) = FirebaseOptions.Builder()
        .setApiKey(fb.apiKey).setApplicationId(fb.appId).setProjectId(fb.projectId)
        .apply { if (fb.senderId.isNotBlank()) setGcmSenderId(fb.senderId) }
        .build()

    @Synchronized
    private fun listen(app: FirebaseApp, cfg: FamilyConfig, myId: String, myName: String, g: Int) {
        if (g != gen) return
        val f = FirebaseFirestore.getInstance(app).collection("families").document(cfg.familyId)
        fam = f
        f.collection("members").document(myId).set(
            mapOf(
                "d" to enc(JSONObject().put("id", myId).put("name", myName).toString()),
                "ts" to FieldValue.serverTimestamp(),
            )
        )
        regs += f.collection("members").addSnapshotListener { snap, e ->
            if (e != null) { Hub.netStatus.value = "Hata: ${e.message}"; return@addSnapshotListener }
            snap?.documents?.forEach { d ->
                runCatching {
                    val o = JSONObject(dec(d.getString("d")!!))
                    Hub.onMember(o.getString("id"), o.optString("name"))
                }
            }
        }
        regs += f.collection("inbox").document(myId).collection("m")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, e ->
                if (e != null) { Hub.netStatus.value = "Hata: ${e.message}"; return@addSnapshotListener }
                if (snap == null) return@addSnapshotListener
                Hub.netStatus.value = if (snap.metadata.isFromCache) "Çevrimdışı, bekliyor" else "Bağlı"
                for (d in snap.documents) {
                    val data = d.getString("d")
                    val json = data?.let { runCatching { dec(it) }.getOrNull() }
                    if (json != null) {
                        val jsonStr: String = json
                        Thread { Hub.receive(jsonStr, "net") }.start()
                    }
                    d.reference.delete()
                }
            }
    }

    /** Queues the message (Firestore keeps it locally until there is internet). */
    fun sendTo(memberId: String, payload: String): Boolean {
        val f = fam ?: return false
        f.collection("inbox").document(memberId).collection("m").add(
            mapOf(
                "d" to enc(payload),
                "ts" to FieldValue.serverTimestamp(),
                // Optional Firestore TTL policy on "exp" removes undelivered messages after 7 days.
                "exp" to Timestamp(Date(System.currentTimeMillis() + INBOX_TTL_MS)),
            )
        )
        return true
    }

    @Synchronized
    fun stop() {
        gen++
        regs.forEach { it.remove() }
        regs.clear()
        fam = null
        Hub.netStatus.value = "Kapalı"
    }

    fun leave(myId: String) {
        fam?.collection("members")?.document(myId)?.delete()
    }

    private fun enc(s: String) = Crypto.b64(Crypto.encrypt(key, s.toByteArray()))
    private fun dec(s: String) = String(Crypto.decrypt(key, Crypto.unb64(s)))
}
