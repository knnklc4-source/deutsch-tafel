package com.aile.mesaj

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Direct phone-to-phone transport over Bluetooth Classic (RFCOMM).
 * Phones must be paired once in Android's Bluetooth settings. Every frame is encrypted with
 * the family key, so a paired phone without the family key cannot read or send anything.
 */
@SuppressLint("MissingPermission")
object Bt {
    private val SERVICE_UUID: UUID = UUID.fromString("6f2b8a4e-3c1d-4b7a-9e5f-1a2b3c4d5e6f")
    private const val MAX_FRAME = 256 * 1024

    private class Conn(val socket: BluetoothSocket) {
        val out = DataOutputStream(socket.outputStream)
        val inp = DataInputStream(socket.inputStream)
        val address: String = socket.remoteDevice.address
        @Volatile var memberId: String? = null

        @Synchronized
        fun write(b: ByteArray) {
            out.writeInt(b.size); out.write(b); out.flush()
        }

        fun close() { runCatching { socket.close() } }
    }

    private val byMember = ConcurrentHashMap<String, Conn>()
    private val allConns: MutableSet<Conn> = ConcurrentHashMap.newKeySet()
    private val familyAddrs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastTry = ConcurrentHashMap<String, Long>()
    @Volatile private var gen = 0
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var key = ByteArray(0)
    private var myId = ""
    private var myName = ""

    fun start(c: Context, cfg: FamilyConfig, id: String, name: String) {
        key = cfg.key; myId = id; myName = name
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter
            ?: run { Hub.btStatus.value = "Bu telefonda Bluetooth yok"; return }
        if (Build.VERSION.SDK_INT >= 31 &&
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Hub.btStatus.value = "Bluetooth izni verilmedi"; return
        }
        val g = ++gen
        thread(name = "bt-server", isDaemon = true) { serverLoop(adapter, g) }
        thread(name = "bt-client", isDaemon = true) { clientLoop(adapter, g) }
        publish(adapter)
    }

    private fun alive(g: Int) = g == gen

    private fun serverLoop(a: BluetoothAdapter, g: Int) {
        while (alive(g)) {
            if (!a.isEnabled) { sleep(5000); continue }
            try {
                val ss = a.listenUsingRfcommWithServiceRecord("AileMesaj", SERVICE_UUID)
                server = ss
                while (alive(g)) handle(ss.accept(), g)
            } catch (e: Exception) {
                runCatching { server?.close() }
                if (alive(g)) sleep(3000)
            }
        }
    }

    private fun clientLoop(a: BluetoothAdapter, g: Int) {
        while (alive(g)) {
            try {
                if (a.isEnabled) {
                    for (d in a.bondedDevices.orEmpty()) {
                        if (!alive(g)) break
                        if (allConns.any { it.address == d.address }) continue
                        val known = d.address in familyAddrs
                        // Don't keep poking headphones, cars, watches…
                        val major = d.bluetoothClass?.majorDeviceClass
                        val maybePhone = major == null || major == BluetoothClass.Device.Major.PHONE ||
                            major == BluetoothClass.Device.Major.COMPUTER ||
                            major == BluetoothClass.Device.Major.UNCATEGORIZED
                        if (!known && !maybePhone) continue
                        val wait = if (known) 15_000L else 120_000L
                        val now = System.currentTimeMillis()
                        if (now - (lastTry[d.address] ?: 0L) < wait) continue
                        lastTry[d.address] = now
                        var s: BluetoothSocket? = null
                        try {
                            s = d.createRfcommSocketToServiceRecord(SERVICE_UUID)
                            s.connect()
                            handle(s, g)
                        } catch (e: Exception) {
                            runCatching { s?.close() }
                        }
                    }
                }
                publish(a)
            } catch (_: Exception) {
            }
            sleep(5000)
        }
    }

    private fun handle(s: BluetoothSocket, g: Int) {
        val c = Conn(s)
        allConns += c
        thread(name = "bt-conn", isDaemon = true) {
            try {
                c.write(Crypto.encrypt(key, JSONObject().put("t", "hello").put("id", myId).put("name", myName).toString().toByteArray()))
                while (alive(g)) {
                    val n = c.inp.readInt()
                    if (n <= 0 || n > MAX_FRAME) break
                    val buf = ByteArray(n)
                    c.inp.readFully(buf)
                    val json = String(Crypto.decrypt(key, buf)) // throws if not from our family
                    val o = JSONObject(json)
                    when (o.optString("t")) {
                        "hello" -> {
                            val id = o.getString("id")
                            c.memberId = id
                            byMember[id] = c
                            familyAddrs += c.address
                            Hub.onMember(id, o.optString("name"))
                            publish(null)
                        }
                        "msg" -> Hub.receive(json, "bt")
                    }
                }
            } catch (_: Exception) {
            } finally {
                c.close()
                allConns -= c
                c.memberId?.let { byMember.remove(it, c) }
                publish(null)
            }
        }
    }

    private fun publish(a: BluetoothAdapter?) {
        Hub.btPeers.value = byMember.keys.toSet()
        Hub.btStatus.value = when {
            a != null && !a.isEnabled -> "Bluetooth kapalı"
            byMember.isEmpty() -> "Yakında kimse yok"
            else -> "${byMember.size} kişi bağlı"
        }
    }

    /** True if written to a live Bluetooth connection with this member. */
    fun sendTo(memberId: String, payload: String): Boolean {
        val c = byMember[memberId] ?: return false
        return try {
            c.write(Crypto.encrypt(key, payload.toByteArray()))
            true
        } catch (e: Exception) {
            c.close()
            false
        }
    }

    fun stop() {
        gen++
        runCatching { server?.close() }
        allConns.forEach { it.close() }
        allConns.clear()
        byMember.clear()
        Hub.btPeers.value = emptySet()
        Hub.btStatus.value = "Kapalı"
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}
