# RetroRadyo — reklamsız internet radyosu

Bu proje Xiaomi 13 gibi Android telefonlar için hazırlanmış bir başlangıç projesidir.
Uygulama içinde reklam SDK'sı veya üyelik sistemi yoktur. Radyo yayınlarının kendi reklamları olabilir.

## APK oluşturma ve tekrar tekrar güncelleme
GitHub Actions > **Build Android APK > Run workflow** ile APK'yı oluştur; secret, anahtar üretme veya GitHub ayarı gerekmez. **Artifacts > app-debug** dosyasını indir, ZIP'i aç ve `app-debug.apk` dosyasını telefona yükle. Her Actions çalıştırması ve Android Studio debug derlemesi depodaki aynı sabit test anahtarını kullanır; böylece sonraki kurulumlar uygulamanın üzerine güncellenebilir.

Bu sabit anahtar depoda bulunduğundan APK'lar kişisel/test dağıtımı içindir; Play Store veya güvenli üretim dağıtımında kullanma. Daha önce başka imzayla kurulmuş uygulamadan ilk geçişte Android bir kez imza çakışması gösterebilir. Eski uygulamayı **bir defa** kaldırıp yeni APK'yı yükle; kaldırma özel istasyonları/favorileri siler. Bundan sonra aynı GitHub workflow'undan gelen APK'lar için kaldırma gerekmez. Android Studio'dan da yalnızca debug APK üret; release APK farklı imza taşıyabilir.

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
