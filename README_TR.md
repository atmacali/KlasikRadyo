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
- Favori işaretleme
- Şarja takıldığında görünür uygulamada son seçili istasyonu otomatik başlatma ve ekranı açık tutma
- Şarj kesildiğinde yayını durdurup uygulama görevini kapatma
- İlk prototipte uygulama açıkken yayın çalma
- Reklam/analitik SDK'sı yok

Android'in arka planda uygulama açma kısıtlamaları nedeniyle uygulama ekranda değilken şarja takıldığında
otomatik açılış garanti edilemez. Bu durumda Android bildirimi gösterilir; radyoyu başlatmak için bildirime
dokunmak gerekir. Bunun çalışması için uygulama bildirimlerine izin verilmelidir.

Not: Hazır istasyonların yayın adresleri zaman içinde değişebilir; bir istasyon açılmazsa başka bir yayın URL'si eklenebilir.
Bu ilk prototip test amaçlıdır; ekran kapalıyken kesintisiz çalma ve telefon açılışında otomatik başlatma henüz tamamlanmış özellikler değildir. Bunlar sonraki sürümde Android medya servisi ve Xiaomi otomatik başlatma izinleriyle eklenmelidir.
