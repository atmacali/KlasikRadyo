# RetroRadyo — reklamsız internet radyosu

Bu proje Xiaomi 13 gibi Android telefonlar için hazırlanmış bir başlangıç projesidir.
Uygulama içinde reklam SDK'sı veya üyelik sistemi yoktur. Radyo yayınlarının kendi reklamları olabilir.

## APK oluşturmanın en kolay yolu
Bu ortamda Android SDK/Gradle olmadığı için burada derlenmiş APK üretemiyorum. Projeye GitHub Actions yapılandırması ekledim:

1. ZIP'i bilgisayarında aç.
2. İçindeki `RetroRadyo` klasörünü yeni bir GitHub deposuna yükle.
3. GitHub deposunda **Actions** sekmesini aç. `Build Android APK` iş akışı otomatik çalışır.
4. İşlem bitince çalıştırmaya tıkla ve **Artifacts** bölümünden `RetroRadyo-debug-apk` dosyasını indir.
5. ZIP içindeki `app-debug.apk` dosyasını Xiaomi 13'e gönderip aç. Android, ilk seferde bu kaynağa ait uygulama yükleme izni isteyebilir.

## Android Studio ile derleme
Android Studio'da bu klasörü açıp Gradle senkronizasyonunu tamamla. Ardından
**Build > Build Bundle(s) / APK(s) > Build APK(s)** seç.

## Özellikler
- Koyu temalı, dokunmatik radyo ekranı
- Örnek istasyonlar
- Yayın URL'si ile özel istasyon ekleme
- Her kanalı düzenleme, silme ve varsayılanları geri yükleme
- Favori işaretleme (kanal düzenlenirken korunur)
- Bluetooth kulaklık/medya kontrolleriyle oynat/duraklat ve ileri/geri kanal değiştirme
- Şarja takıldığında son seçili istasyonu otomatik başlatma ve uygulama görünürken ekranı açık tutma
- Radyo çalarken arka planda şarj olaylarını izleme; şarj kesildiğinde yayını durdurup uygulama görevini kapatma
- İlk prototipte uygulama açıkken yayın çalma
- Reklam/analitik SDK'sı yok

Uygulama ekrandayken şarj bağlanırsa seçili kanal otomatik başlar; bağlantı kesilince yayın durur ve uygulama
kapanır. Radyo arka planda çalarken medya servisi de şarj bağlantısını izler; bağlantıda son yayını sürdürür,
bağlantı kesilince durdurup uygulama görevini kapatır.

Android 8 ve üzeri sürümlerde şarj olayları, uygulama kapalıyken manifest alıcısına her zaman iletilmez;
Android 10 ve üzeri sürümlerde arka plandan uygulama ekranı açmak da kısıtlıdır. Bu yüzden uygulama ve medya
servisi çalışmıyorken şarja takınca otomatik açılma garanti edilemez. Uygulama arka planda ama medya servisi
çalışıyorsa bildirim görünür; radyoyu açmak için bildirime dokunun. Bildirim için **Ayarlar > Uygulamalar >
Retro Radyo > Bildirimler** iznini açın. Üreticiye özgü arka planda açılma ayarları yardımcı olabilir, ancak
Android bunları garanti etmez.

Not: Hazır istasyonların yayın adresleri zaman içinde değişebilir; açılmayan bir kanal düzenlenebilir, silinebilir
veya varsayılanlar geri yüklenebilir. Ekran açık kalma yalnızca uygulama görünür durumdayken geçerlidir; telefon
açılışında uygulamayı otomatik başlatma desteklenmez.
