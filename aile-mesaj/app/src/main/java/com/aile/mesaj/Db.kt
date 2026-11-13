package com.aile.mesaj

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local-only store. Message texts are encrypted with the phone's Keystore key. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "aile.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Overwrite deleted rows with zeros instead of just unlinking them.
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msgs(id TEXT PRIMARY KEY, sender TEXT NOT NULL, name TEXT NOT NULL, " +
                "body BLOB NOT NULL, ts INTEGER NOT NULL, via TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX msgs_ts ON msgs(ts)")
        db.execSQL("CREATE TABLE members(id TEXT PRIMARY KEY, name TEXT NOT NULL, seen INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Returns false if a message with this id already exists (duplicate via BT + internet). */
    fun insertMsg(m: Msg): Boolean {
        val cv = ContentValues().apply {
            put("id", m.id); put("sender", m.senderId); put("name", m.senderName)
            put("body", Crypto.localEncrypt(m.text.toByteArray())); put("ts", m.ts); put("via", m.via)
        }
        return writableDatabase.insertWithOnConflict("msgs", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun updateVia(id: String, via: String) {
        writableDatabase.update("msgs", ContentValues().apply { put("via", via) }, "id=?", arrayOf(id))
    }

    fun messages(): List<Msg> =
        readableDatabase.rawQuery("SELECT id,sender,name,body,ts,via FROM msgs ORDER BY ts", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val text = runCatching { String(Crypto.localDecrypt(c.getBlob(3))) }.getOrDefault("(okunamadı)")
                    add(Msg(c.getString(0), c.getString(1), c.getString(2), text, c.getLong(4), c.getString(5)))
                }
            }
        }

    fun deleteOlderThan(cutoff: Long): Int =
        writableDatabase.delete("msgs", "ts < ?", arrayOf(cutoff.toString()))

    fun deleteAllMessages() {
        writableDatabase.delete("msgs", null, null)
        writableDatabase.execSQL("VACUUM")
    }

    fun upsertMember(m: Member) {
        val cv = ContentValues().apply { put("id", m.id); put("name", m.name); put("seen", m.seen) }
        writableDatabase.insertWithOnConflict("members", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun members(): List<Member> =
        readableDatabase.rawQuery("SELECT id,name,seen FROM members ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(Member(c.getString(0), c.getString(1), c.getLong(2))) }
        }

    fun wipe() {
        writableDatabase.delete("msgs", null, null)
        writableDatabase.delete("members", null, null)
        writableDatabase.execSQL("VACUUM")
    }
}
