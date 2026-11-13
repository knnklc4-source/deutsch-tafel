package com.aile.mesaj

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun Root() {
    val cfg by Hub.config.collectAsState()
    val c = cfg
    if (c == null) {
        SetupScreen()
        return
    }
    var screen by rememberSaveable { mutableStateOf("chat") }
    BackHandler(screen != "chat") { screen = if (screen == "invite") "settings" else "chat" }
    when (screen) {
        "settings" -> SettingsScreen(c, onBack = { screen = "chat" }, onInvite = { screen = "invite" })
        "invite" -> InviteScreen(c, onBack = { screen = "settings" })
        else -> ChatScreen(c, onSettings = { screen = "settings" })
    }
}

private fun scanOptions() = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Aile davet QR kodunu okutun")
    .setBeepEnabled(false)
    .setOrientationLocked(false)

// ---------------------------------------------------------------- Setup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    var name by rememberSaveable { mutableStateOf(Hub.myName) }
    var mode by rememberSaveable { mutableStateOf("") }
    var familyName by rememberSaveable { mutableStateOf("Ailem") }
    var fbText by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun join(text: String) {
        error = try {
            require(name.isNotBlank()) { "Önce adını yaz" }
            val f = FamilyConfig.fromInvite(text)
            Hub.setMyName(name.trim())
            Hub.applyConfig(ctx, f)
            null
        } catch (e: Exception) { e.message ?: "Geçersiz kod" }
    }

    fun create() {
        error = try {
            require(name.isNotBlank()) { "Önce adını yaz" }
            val fb = if (fbText.isBlank()) null else FbConfig.fromGoogleServices(fbText)
            Hub.setMyName(name.trim())
            Hub.applyConfig(ctx, FamilyConfig.create(familyName.trim().ifBlank { "Ailem" }, fb))
            null
        } catch (e: Exception) { e.message ?: "Hata" }
    }

    val pickJson = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) fbText = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { code = it; join(it) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Aile Mesaj") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sadece ailen için özel mesajlaşma. Mesajlar yakındayken Bluetooth ile, uzaktayken internet üzerinden şifreli gider ve yalnızca telefonlarda saklanır.")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Adın") }, singleLine = true)
            when (mode) {
                "join" -> {
                    Button({ scan.launch(scanOptions()) }, Modifier.fillMaxWidth()) { Text("QR kodu okut") }
                    Text("veya sana gönderilen davet kodunu yapıştır:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("Davet kodu") })
                    Button({ join(code) }, Modifier.fillMaxWidth(), enabled = code.isNotBlank()) { Text("Katıl") }
                    TextButton({ mode = ""; error = null }) { Text("Geri") }
                }
                "create" -> {
                    OutlinedTextField(familyName, { familyName = it }, Modifier.fillMaxWidth(), label = { Text("Aile adı") }, singleLine = true)
                    Text(
                        "İnternet üzerinden mesajlaşmak için Firebase ayar dosyası (google-services.json) gerekir. " +
                            "Boş bırakırsan uygulama sadece Bluetooth ile çalışır; sonra Ayarlar'dan ekleyebilirsin.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton({ pickJson.launch("*/*") }, Modifier.fillMaxWidth()) {
                        Text(if (fbText.isBlank()) "google-services.json seç" else "Dosya seçildi ✓ (değiştir)")
                    }
                    Button({ create() }, Modifier.fillMaxWidth()) { Text("Aileyi oluştur") }
                    TextButton({ mode = ""; error = null }) { Text("Geri") }
                }
                else -> {
                    Button({ mode = "join" }, Modifier.fillMaxWidth()) { Text("Aileye katıl") }
                    OutlinedButton({ mode = "create" }, Modifier.fillMaxWidth()) { Text("Yeni aile oluştur (ilk kişi)") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---------------------------------------------------------------- Chat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(cfg: FamilyConfig, onSettings: () -> Unit) {
    val msgs by Hub.messages.collectAsState()
    val net by Hub.netStatus.collectAsState()
    val bt by Hub.btStatus.collectAsState()
    val ret by Hub.retention.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()

    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            withContext(Dispatchers.IO) { Hub.cleanup() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(cfg.name)
                        Text("İnternet: $net · Bluetooth: $bt", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { IconButton(onSettings) { Icon(Icons.Default.Settings, "Ayarlar") } },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    text, { text = it }, Modifier.weight(1f),
                    placeholder = { Text("Mesaj yaz…") }, maxLines = 5,
                )
                IconButton(
                    onClick = {
                        val t = text
                        text = ""
                        Hub.scope.launch { Hub.send(t) }
                    },
                    enabled = text.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Gönder") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (ret != Retention.OFF) {
                Text(
                    if (ret == Retention.DAY) "Mesajlar 24 saat sonra bu telefondan silinir."
                    else "Mesajlar 7 gün sonra bu telefondan silinir.",
                    Modifier.fillMaxWidth().padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (msgs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Henüz mesaj yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(), state = list,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { items(msgs, key = { it.id }) { Bubble(it) } }
            }
        }
    }
}

private fun timeLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val f = if (sameDay) "HH:mm" else "dd.MM HH:mm"
    return SimpleDateFormat(f, Locale.getDefault()).format(Date(ts))
}

private fun viaLabel(via: String) = when (via) {
    "bt" -> "Bluetooth"
    "net" -> "İnternet"
    "bt+net" -> "Bluetooth + İnternet"
    "sending" -> "gönderiliyor…"
    "none" -> "⚠ kimseye ulaşmadı"
    else -> via
}

@Composable
fun Bubble(m: Msg) {
    val mine = m.senderId == Hub.myId
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!mine) Text(
                    m.senderName.ifBlank { "?" }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                )
                Text(m.text)
                Text(
                    timeLabel(m.ts) + " · " + viaLabel(m.via),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(cfg: FamilyConfig, onBack: () -> Unit, onInvite: () -> Unit) {
    val ctx = LocalContext.current
    val ret by Hub.retention.collectAsState()
    val keep by Hub.keepRunning.collectAsState()
    val members by Hub.members.collectAsState()
    val peers by Hub.btPeers.collectAsState()
    val net by Hub.netStatus.collectAsState()
    val bt by Hub.btStatus.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    var fbText by remember { mutableStateOf("") }
    var fbMsg by remember { mutableStateOf<String?>(null) }

    fun applyFb(text: String) {
        fbMsg = try {
            val t = text.trim()
            val newCfg = if (t.startsWith("AILE1:")) FamilyConfig.fromInvite(t)
            else cfg.copy(fb = FbConfig.fromGoogleServices(t))
            Hub.applyConfig(ctx, newCfg)
            fbText = ""
            "Kaydedildi ✓"
        } catch (e: Exception) { e.message ?: "Hata" }
    }

    val pickJson = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.onSuccess { applyFb(it) }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { applyFb(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Mesajları otomatik sil")
            Retention.entries.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().selectable(ret == r, onClick = { Hub.setRetention(r) }).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(ret == r, null)
                    Text(r.label, Modifier.padding(start = 8.dp))
                }
            }
            Hint("Seçilen süreden eski mesajlar bu telefondan kalıcı olarak silinir. Silme her saat başı ve uygulama açıldığında kontrol edilir.")
            OutlinedButton({ confirm = "delete" }) { Text("Tüm mesajları şimdi sil") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Aile üyeleri")
            if (members.isEmpty()) Hint("Henüz başka üye görünmüyor.")
            members.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name.ifBlank { "(adsız)" }, Modifier.weight(1f))
                    if (m.id in peers) Text("Bluetooth ile yakında", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Button(onInvite) { Text("Yeni üye davet et (QR)") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Bağlantı")
            Text("İnternet: $net")
            Text("Bluetooth: $bt")
            Hint("Bluetooth için aile telefonlarını bir kez Android'in Bluetooth ayarlarından eşleştirin. Uygulama, eşleşmiş aile telefonlarını kendisi bulur.")
            OutlinedButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) {
                Text("Bluetooth ayarlarını aç")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Uygulama kapalıyken de mesaj al", Modifier.weight(1f))
                Switch(keep, { Hub.setKeepRunning(it) })
            }
            Hint("Açıkken bildirim çubuğunda küçük bir \"Aile Mesaj açık\" bildirimi durur.")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("İnternet (Firebase)")
            Text(cfg.fb?.let { "Proje: ${it.projectId}" } ?: "Ayarlı değil: mesajlar sadece Bluetooth ile gider.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickJson.launch("*/*") }) { Text("Dosya seç") }
                OutlinedButton({ scan.launch(scanOptions()) }) { Text("Yeni davet QR'ı okut") }
            }
            OutlinedTextField(
                fbText, { fbText = it }, Modifier.fillMaxWidth(), maxLines = 4,
                label = { Text("veya google-services.json / davet kodu yapıştır") },
            )
            if (fbText.isNotBlank()) Button({ applyFb(fbText) }) { Text("Uygula") }
            fbMsg?.let { Text(it) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Adın: ${Hub.myName}")
            TextButton(
                { confirm = "leave" },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Aileden ayrıl ve her şeyi sil") }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (confirm) {
        "delete" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Tüm mesajlar silinsin mi?") },
            text = { Text("Bu telefondaki bütün mesajlar kalıcı olarak silinir. Diğer üyelerin telefonları etkilenmez.") },
            confirmButton = { TextButton({ confirm = null; Hub.scope.launch { Hub.deleteAllMessages() } }) { Text("Sil") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Vazgeç") } },
        )
        "leave" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Aileden ayrıl?") },
            text = { Text("Aile anahtarı, üyeler ve tüm mesajlar bu telefondan silinir. Tekrar katılmak için yeni davet QR'ı gerekir.") },
            confirmButton = { TextButton({ confirm = null; Hub.leave(ctx) }) { Text("Ayrıl") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(t: String) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------- Invite

fun qrBitmap(text: String, size: Int = 800): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(cfg: FamilyConfig, onBack: () -> Unit) {
    val code = remember(cfg) { cfg.inviteCode() }
    val bmp = remember(code) { qrBitmap(code).asImageBitmap() }
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Üye davet et") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Yeni aile üyesi uygulamayı kursun, \"Aileye katıl\"a dokunsun ve bu kodu okutsun.")
            Image(bmp, "Davet QR kodu", Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp))
            Text(
                "⚠ Bu kod ailenin anahtarıdır. Sadece aile üyelerine göster, fotoğrafını paylaşma.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton({ clip.setText(AnnotatedString(code)); copied = true }) {
                Text(if (copied) "Kopyalandı ✓" else "Kodu kopyala (uzaktaki üye için)")
            }
        }
    }
}
