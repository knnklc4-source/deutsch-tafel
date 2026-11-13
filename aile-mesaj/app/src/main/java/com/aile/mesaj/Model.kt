package com.aile.mesaj

import org.json.JSONObject

data class Msg(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val ts: Long,
    /** received: "bt" / "net". own: "sending", "bt", "net", "bt+net", "none". */
    val via: String,
)

data class Member(val id: String, val name: String, val seen: Long)

enum class Retention(val ms: Long, val label: String) {
    OFF(0L, "Kapalı (silme)"),
    DAY(24L * 3600_000L, "Günlük (24 saatten eski mesajlar)"),
    WEEK(7L * 24L * 3600_000L, "Haftalık (7 günden eski mesajlar)"),
}

data class FbConfig(val apiKey: String, val appId: String, val projectId: String, val senderId: String) {
    fun toJson(): JSONObject =
        JSONObject().put("a", apiKey).put("i", appId).put("p", projectId).put("s", senderId)

    companion object {
        fun fromJson(o: JSONObject) =
            FbConfig(o.getString("a"), o.getString("i"), o.getString("p"), o.optString("s"))

        /** Reads the google-services.json file downloaded from the Firebase console. */
        fun fromGoogleServices(text: String): FbConfig {
            val o = try {
                JSONObject(text.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("Bu bir google-services.json dosyası değil")
            }
            val pi = o.getJSONObject("project_info")
            val clients = o.getJSONArray("client")
            var client = clients.getJSONObject(0)
            for (i in 0 until clients.length()) {
                val c = clients.getJSONObject(i)
                val pkg = c.optJSONObject("client_info")?.optJSONObject("android_client_info")
                    ?.optString("package_name")
                if (pkg == "com.aile.mesaj") client = c
            }
            return FbConfig(
                apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key"),
                appId = client.getJSONObject("client_info").getString("mobilesdk_app_id"),
                projectId = pi.getString("project_id"),
                senderId = pi.optString("project_number"),
            )
        }
    }
}

data class FamilyConfig(val familyId: String, val name: String, val key: ByteArray, val fb: FbConfig?) {
    fun toJson(): JSONObject = JSONObject()
        .put("fid", familyId).put("n", name).put("k", Crypto.b64(key))
        .apply { fb?.let { put("fb", it.toJson()) } }

    fun inviteCode(): String = PREFIX + Crypto.b64(toJson().put("v", 1).toString().toByteArray())

    companion object {
        private const val PREFIX = "AILE1:"

        fun fromJson(o: JSONObject) = FamilyConfig(
            o.getString("fid"), o.getString("n"), Crypto.unb64(o.getString("k")),
            o.optJSONObject("fb")?.let { FbConfig.fromJson(it) },
        )

        fun fromInvite(code: String): FamilyConfig {
            val c = code.trim()
            require(c.startsWith(PREFIX)) { "Geçersiz davet kodu" }
            return try {
                fromJson(JSONObject(String(Crypto.unb64(c.removePrefix(PREFIX)))))
            } catch (e: Exception) {
                throw IllegalArgumentException("Davet kodu bozuk veya eksik")
            }
        }

        fun create(name: String, fb: FbConfig?) =
            FamilyConfig(Crypto.hex(Crypto.randomBytes(16)), name, Crypto.randomBytes(32), fb)
    }
}
