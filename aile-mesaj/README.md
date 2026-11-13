# Aile Mesaj

Sadece aile üyeleri için Android mesajlaşma uygulaması.

- **Sadece aile:** Katılmak için ailedeki birinin telefonundaki davet QR kodunu okutmak gerekir. QR kodu, 256 bitlik aile anahtarını taşır.
- **Bluetooth:** Eşleşmiş aile telefonları yakındaysa mesajlar internetsiz, doğrudan gider (RFCOMM, aile anahtarıyla şifreli).
- **İnternet:** Telefonlar uzaktaysa mesajlar Firebase Firestore üzerinden gider. Sunucu yalnızca şifreli veri görür. Mesaj alıcıya ulaşınca sunucudan silinir.
- **Yerel, kısa süreli hafıza:** Mesajlar yalnızca telefonda tutulur. Metinler Android Keystore anahtarıyla şifrelenir, yedeklemeye girmez.
- **Otomatik silme:** Ayarlar → Kapalı / Günlük (24 saatten eski) / Haftalık (7 günden eski). Silme her saat başı, uygulama açılınca ve servis açıkken 5 dakikada bir çalışır.

## Firebase kurulumu (bir kez, aileyi kuran kişi yapar)

1. https://console.firebase.google.com → **Proje ekle** (ör. `aile-mesaj`). Analytics gerekmez.
2. **Build → Authentication → Başlayın → Sign-in method → Anonymous → Etkinleştir.**
3. **Build → Firestore Database → Veritabanı oluştur** (production mode, size yakın bölge, ör. `eur3`).
4. Firestore → **Rules** sekmesine aşağıdakini yapıştırıp **Yayınla**:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /families/{fid}/members/{mid} {
         allow read, write: if request.auth != null;
       }
       match /families/{fid}/inbox/{mid}/m/{msg} {
         allow read, write: if request.auth != null;
       }
     }
   }
   ```
5. Proje ayarları (⚙) → **Uygulama ekle → Android**. Paket adı: `com.aile.mesaj` → **google-services.json** dosyasını indir.
6. Uygulamada "Yeni aile oluştur" ekranında bu dosyayı seç. Diğer üyeler dosyaya ihtiyaç duymaz; ayar QR koduyla onlara geçer.
7. (İsteğe bağlı) Firestore → **TTL policies** → koleksiyon grubu `m`, alan `exp`. Böylece hiç teslim edilemeyen mesajlar 7 gün sonra sunucudan da silinir.

Ücretsiz Spark planı bir aile için fazlasıyla yeterlidir.

## Bluetooth

Aile telefonlarını bir kez Android'in Bluetooth ayarlarından eşleştirin. Uygulama eşleşmiş telefonları kendisi dener. Karşı tarafta da uygulama çalışıyorsa bağlantı kurulur. Ayarlar ekranında "Bluetooth ile yakında" yazar.

## Derleme

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

İmzalama için `keystore.properties` + `.jks` dosyası gerekir (git'e konmaz). Bunlar yoksa debug anahtarıyla imzalanır. Güncellemelerin eski sürümün üzerine kurulabilmesi için hep aynı anahtar kullanılmalıdır.
