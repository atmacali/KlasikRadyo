# RetroRadyo — reklamsız internet radyosu

Bu proje Xiaomi 13 gibi Android telefonlar için hazırlanmış bir başlangıç projesidir.
Uygulama içinde reklam SDK'sı veya üyelik sistemi yoktur. Radyo yayınlarının kendi reklamları olabilir.

## APK oluşturma ve tekrar tekrar güncelleme
GitHub Actions artık her çalıştırmada yeni debug imzası üretmek yerine, senin sakladığın aynı release anahtarıyla imzalı APK oluşturur. Bir kez anahtar üretip GitHub deposuna Actions secret olarak ekle:

1. Bilgisayarında (Android Studio/Java kurulu olmalı) şu komutla anahtar dosyası oluştur. Parola sorulduğunda güçlü bir parola seç; unutma:

   ```powershell
   keytool -genkeypair -v -keystore .\retroradyo-release.jks -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10000 -alias retroradyo
   ```

2. PowerShell'de dosyanın tek satır Base64 değerini üret ve kopyala:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\retroradyo-release.jks)))
   ```

3. GitHub deposunda **Settings > Secrets and variables > Actions > New repository secret** yolunu açıp şu dört secret'ı ekle:
   - `ANDROID_KEYSTORE_BASE64` — 2. adımdaki Base64 çıktısı
   - `ANDROID_KEYSTORE_PASSWORD` — anahtar deposu parolası
   - `ANDROID_KEY_ALIAS` — `retroradyo`
   - `ANDROID_KEY_PASSWORD` — anahtar parolası (PKCS12 için depo parolasıyla aynı olabilir)

   Anahtar dosyasını ve parolaları depoya veya sohbete koyma. `.jks`, `.keystore` ve `.p12` dosyaları Git tarafından yok sayılır. Anahtar dosyasını güvenli bir yerde ayrıca yedekle; bu anahtar kaybolursa yeni imzalı APK'lar mevcut kurulumun üzerine güncellenemez.

4. Güncellenmiş kodu GitHub'a gönder. **Actions > Build Android APK > Run workflow** bölümünden aynı branch'i seçip çalıştır.
5. İşlem bitince **Artifacts** bölümünden `app-release` dosyasını indir, ZIP'i aç ve `app-release.apk` dosyasını telefona yükle. Yeni telefonda yalnızca APK kaynağı için Android'in istediği yükleme iznini ver.

İlk sabit-imzalı APK'ya geçerken telefondaki eski, her Actions çalıştırmasında değişen debug imzası nedeniyle Android bir kez "imza çakışması" gösterebilir. O zaman eski uygulamayı **bir defa** kaldırıp yeni `app-release.apk` dosyasını yükle; kaldırma kayıtlı özel istasyonları/favorileri siler. Bundan sonra hep aynı GitHub workflow'u ve aynı dört secret ile çıkan APK'ları kur; imza çakışması tekrarlanmamalı. Yerel Android Studio debug APK'sı ile GitHub release APK'sını dönüşümlü kurma, imzaları farklıdır.

## Android Studio ile debug derleme
Android Studio'da bu klasörü açıp Gradle senkronizasyonunu tamamla. Ardından
**Build > Build Bundle(s) / APK(s) > Build APK(s)** seç.

## Özellikler
- Koyu temalı, dokunmatik radyo ekranı
- Örnek istasyonlar
- Yayın URL'si ile özel istasyon ekleme
- Her kanalı düzenleme, silme ve varsayılanları geri yükleme
- İstasyon ekleme, durdurma ve varsayılanları geri yükleme düğmeleri listenin en altında
- Favori işaretleme (kanal düzenlenirken korunur)
- Bluetooth kulaklık/medya kontrolleriyle oynat/duraklat ve ileri/geri kanal değiştirme
- Uygulama ilk açıldığında başlayan, sürekli bildirim göstererek şarj bağlantısını izleyen foreground service
- Şarja takılınca son seçili istasyonu başlatma; fiş çekilince yayını durdurup ekranı kapatma, izleme servisini sürdürme
- Daha az ekran ışığı için siyah AMOLED arka planı; ses/kanal kontrolleri her zaman görünür
- İlk prototipte uygulama açıkken yayın çalma
- Reklam/analitik SDK'sı yok

İzleme servisi uygulamayı ilk kez açtığında başlar ve fişi çekince radyo uygulama ekranı kapansa da servisi ve
kalıcı, sessiz bildirimi çalışır bırakır. Bu sayede servis şarj olayını alıp seçili kanalı arka planda başlatabilir;
bildirime dokunmak radyo ekranını açar. Android 10 ve üzeri uygulamanın şarj olayıyla ekranı kendi kendine
açmasını kısıtlar; radyo başlatma ile tam ekranı açma ayrı şeylerdir, otomatik ekran açılması garanti edilemez.

Bildirim görünürlüğü için **Ayarlar > Uygulamalar > Uygulamaları yönet > Retro Radyo > Bildirimler** iznini aç.
Xiaomi 13'te ayrıca **Ayarlar > Uygulamalar > İzinler > Arka planda otomatik başlatma > Retro Radyo** seçeneğini
etkinleştir ve **Pil tasarrufu > Kısıtlama yok** seçeneğini kullan. MIUI/HyperOS sürümüne göre adlar değişebilir.
Android Ayarlar'dan uygulamayı zorla durdurmak servisi durdurur; yeniden açmak için uygulama simgesine dokun.
Telefon yeniden başlatıldıktan sonra da izlemeyi başlatmak için uygulamayı bir kez aç.

Sürekli bildirimli servis pil kullanır. Radyo uygulama ekrandayken ekran açık kalır; siyah AMOLED görünümü ışık
kullanımını azaltmaya yardımcı olur, ancak açık ekran ısınmayı/pil tüketimini tamamen önleyemez. Daha serin
kullanım için ekran parlaklığını düşür; uygulama görünürken ekranın kapanması da Android ekran-açık isteği
nedeniyle engellenir.

Not: Hazır istasyonların yayın adresleri zaman içinde değişebilir; açılmayan bir kanal düzenlenebilir, silinebilir
veya varsayılanlar geri yüklenebilir. Ekran açık kalma yalnızca uygulama görünür durumdayken geçerlidir; telefon
açılışında uygulamayı otomatik başlatma desteklenmez.
