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
- İstasyon ekleme, durdurma ve varsayılanları geri yükleme komutları **Menü** içinde
- “AMOLED EKRAN KORUYUCU” düğmesiyle saat, kanal adı ve iki çevirmeli kontrol dışında kalan arayüzü gizleme; çıkmak için kanal adına dokunma
- Kanal düğmesini 90° çevirince bir önceki/sonraki kanala geçme; her adımda açı sayacını sıfırlama ve dokunmayı tıklamadan ayırma
- Menüde **Kanal sırasını düzenle** seçilince kanalları erişilebilir yukarı/aşağı oklarıyla taşıma; sıra cihazda saklanır (favoriler kendi gruplarında kalır)
- Telefon yatay çevrildiğinde daha büyük ve aralıklı ses/kanal düğmeleri
- **Menü** içinden şarja takılınca radyo başlatma, şarjdan çıkarınca durdurma, Bluetooth otomatik çalma ve şarja takılınca AMOLED ekran koruyucu seçeneklerini ayrı ayrı açıp kapatma
- Menüden GitHub'daki ortak `stations.json` kanal kataloğunu elle güncelleme; silinen katalog kanallarını gizli listeden isteğe bağlı geri etkinleştirme
- Seçili kanalı GitHub öneri formuna veya yapılandırılmış Worker API üzerinden, açık kullanıcı onayıyla gönderme
- Ses düğmesinde seçilen son ses seviyesini uygulama açılışları ve servis yeniden başlatmaları arasında koruma
- Favori işaretleme (kanal düzenlenirken korunur)
- Bluetooth kulaklık/medya kontrolleriyle oynat/duraklat ve ileri/geri kanal değiştirme
- Uygulama ilk açıldığında başlayan, sürekli bildirim göstererek şarj bağlantısını izleyen foreground service
- Varsayılan olarak şarja takılınca son seçili istasyonu başlatma; fiş çekilince yayını durdurup ekranı kapatma, izleme servisini sürdürme (menüden ayrı ayrı ayarlanabilir)
- Bluetooth kulaklık/cihaz bağlanınca son seçili radyoyu otomatik çalma (menüden açılır; Android 12+ Bluetooth izni gerekir)
- Siyah-beyaz arayüz; AMOLED koruyucuda yalnızca saat, istasyon adı ve çok sade iki döner kontrol görünür. Görüntü 45 saniyede bir en fazla 10 piksel kayar.
- İlk prototipte uygulama açıkken yayın çalma
- Reklam/analitik SDK'sı yok

İzleme servisi uygulamayı ilk kez açtığında başlar ve fişi çekince radyo uygulama ekranı kapansa da servisi ve
kalıcı, sessiz bildirimi çalışır bırakır. Menüde şarja takılınca başlatma ve fiş çekilince durdurup ekranı kapatma
seçenekleri varsayılan olarak açıktır; şarjda AMOLED koruyucu varsayılan olarak kapalıdır. Bluetooth otomatik çalma varsayılan olarak kapalıdır; açıldığında Android 12+
Bluetooth cihazlarına erişim izni ister. Şarj ekran koruyucusu açık ve uygulama görünür durumdaysa fiş
takılınca koruyucuya geçer; fiş çıkarılınca koruyucudan çıkar ve mevcut durdurma/ekranı kapatma tercihi
uygulanır. Uygulama açıldığında, çalmıyorsa son seçili istasyonu yeniden başlatır;
zaten çalıyorsa akışı kesip tekrar başlatmaz. Seçili istasyon, özel kanallar ve favoriler korunur.
İlk kurulumda ses varsayılan olarak %70'tir; ses düğmesini çevirdikten sonra son değer saklanır ve sonraki açılışlarda korunur.

MacroDroid ile Bluetooth bağlanınca uygulamayı açmak için bir makro oluştur:
1. **Tetikleyici > Bağlantı > Bluetooth olayı > Cihaza bağlandı** seçip kulaklığını seç.
2. **Eylem > Uygulamalar > Uygulamayı başlat** seçip **Retro Radyo**'yu seç.
3. Makroyu kaydet ve Retro Radyo ayar menüsünde **Bluetooth bağlanınca son radyoyu çal** seçeneğini aç. Android 12+ sorarsa Yakındaki cihazlar/Bluetooth iznini ver.

Uygulama zaten açıksa ses akışı yeniden başlatılmaz; uygulama kapalıysa MacroDroid'in uygulamayı açması son seçili kanalı çaldırır. Otomatik çalma seçeneği ayrıca uygulamanın servisinin aldığı Bluetooth bağlantı olaylarında da çalışır. MacroDroid arka planda uygulama açamıyorsa Xiaomi ayarlarında MacroDroid için arka planda otomatik başlatma ve pil kısıtlaması olmamasına izin ver; varsa arka planda pencere açma iznini de etkinleştir.

Menüde **Kanal listesini güncelle** seçilince herkese açık
`https://github.com/atmacali/KlasikRadyo/blob/main/stations.json` kataloğu indirilir. Kanal adı/adresi
değişiklikleri uygulanır; favoriler, özel kanallar ve kullanıcı tarafından düzenlenmiş katalog alanları korunur.
Katalogda artık bulunmayan veya kullanıcı tarafından silinmiş katalog kanalları listenin altındaki kapalı
**Gizli kanallar** bölümüne alınır; **Etkinleştir** ile tekrar görünür yapılır. Güncelleme düğmesi ağ bağlantısı
gerektirir; GitHub'a kataloğu değiştirmek için depo sahibi `stations.json` dosyasını günceller ve GitHub'a gönderir.

**Seçili kanalı GitHub'a öner** düğmesi, seçili kanal adı ve yayın adresi önceden doldurulmuş bir GitHub issue
formu açar; bu eski, elle gönderilen yol olarak kalır. Worker API kurulduktan sonra menüdeki adres alanına
`https://<worker-adiniz>.workers.dev` adresini yapıştırıp **API adresini kaydet** seçin. Ardından
**API ile GitHub'a kanal önerisi gönder** düğmesine basıp onaylayınca Worker yeni bir GitHub issue oluşturur
ve issue bağlantısını gösterir. API gönderimi için GitHub'da ayrıca Submit düğmesine basmanız gerekmez.
Uygulamaya GitHub parolası veya token girilmez; Worker token'ı Cloudflare'de gizli tutulur.

### Ücretsiz Cloudflare Worker API kurulumu

Worker kaynak kodu `worker/index.mjs`, yapılandırması `worker/wrangler.toml` içindedir. Kurulumu bir
bilgisayarda, Node.js LTS kurulu iken yap:

1. Depoyu GitHub'a gönder/merge et ve bilgisayara klonla. GitHub deposunda
   **Settings > Developer settings > Personal access tokens > Fine-grained tokens** bölümünde token oluştur.
   Resource owner olarak `atmacali`, yalnızca `KlasikRadyo` deposunu seç; **Repository permissions > Issues**
   iznini **Read and write** yap. Başka izin verme ve mümkün olan kısa son kullanma tarihini seç.
2. Cloudflare hesabı açıp ücretsiz Workers planını etkinleştir. PowerShell/terminalde depo köküne gidip:
   ```powershell
   cd .\worker
   npx wrangler login
   npx wrangler deploy
   npx wrangler secret put GITHUB_TOKEN
   ```
   Son komut sorunca az önce oluşturduğun token'ı terminale yapıştır. `wrangler.toml` içindeki `1001`
   rate-limit namespace numarası Cloudflare hesabında benzersiz olmalı; çakışma uyarısı alırsan boş bir
   pozitif sayı seçip dosyadaki değeri değiştir ve tekrar deploy et. Token yalnızca Worker secret olarak
   saklanır; **uygulamaya, GitHub deposuna veya sohbete yapıştırma**. Worker ayarındaki depo sahibi/ismi
   zaten `atmacali/KlasikRadyo` olarak tanımlıdır. Rate-limit binding aynı Cloudflare konumu başına IP'ye
   yaklaşık dakikada 5 istek uygular; mobil ağlarda aynı dış IP'yi paylaşanlar bu sınırı paylaşabilir.
3. `deploy` çıktısındaki `https://...workers.dev` adresini RetroRadyo **Menü** bölümündeki
   **Cloudflare Worker API adresi** alanına girip kaydet. İstersen önce sonuna `/health` ekleyerek
   `{ "ok": true }` yanıtını kontrol et. Uygulama `/submit` yolunu kendisi ekler.
4. İstasyon seçip **API ile GitHub'a kanal önerisi gönder** seçeneğine bas. Onaydan sonra GitHub issue
   oluşturulur ve bağlantısı uygulamada görünür. Worker'ı değiştirdiğinde `cd .\worker; npx wrangler deploy`
   ile yeniden yayınla. Token sızarsa GitHub'dan iptal edip yeni token'ı `npx wrangler secret put GITHUB_TOKEN`
   ile Worker'a kaydet.

Cloudflare ücretsiz katmanı çoğu kişisel kullanım için yeterlidir; sağlayıcı kota ve koşulları değişebilir.
Worker URL'si herkese açık olduğundan başkaları da çağırabilir; dakikada 5/IP sınırı yaklaşık olup kesin
veya küresel bir kota değildir. Token yalnızca Issues yazabilir. Kötüye kullanım görürsen token'ı iptal et
ve Worker'ı sil veya sınırı düşür. Worker GitHub'da doğrudan issue açar, bu yüzden uygulamadaki onay
penceresini dikkatle kullan. API çalışmazsa eski GitHub formu kullanılabilir. URL ve istasyon adı Worker'a HTTPS üzerinden gider.

Uygulama arka plandaysa servis yayın başlatabilir. Şarj ekran koruyucusu ayarı açık olsa bile Android uygulama
ekranını kendiliğinden açmayabilir; kalıcı bildirime dokununca şarj ekran koruyucusunu açar. Android 10 ve üzeri arka plandan aktivite açılmasını
kısıtlar: şarja veya Bluetooth'a bağlanınca ekranın kendiliğinden açılması garanti edilemez ve uygulama bu kısıtlamayı aşmaya çalışmaz.

Bildirim görünürlüğü için **Ayarlar > Uygulamalar > Uygulamaları yönet > Retro Radyo > Bildirimler** iznini aç.
Xiaomi 13'te ayrıca **Ayarlar > Uygulamalar > İzinler > Arka planda otomatik başlatma > Retro Radyo** seçeneğini
etkinleştir ve **Pil tasarrufu > Kısıtlama yok** seçeneğini kullan. MIUI/HyperOS sürümüne göre adlar değişebilir.
Android Ayarlar'dan uygulamayı zorla durdurmak servisi durdurur; yeniden açmak için uygulama simgesine dokun.
Telefon yeniden başlatıldıktan sonra da izlemeyi başlatmak için uygulamayı bir kez aç.

Sürekli bildirimli servis pil kullanır. Radyo uygulama ekrandayken ekran açık kalır; AMOLED koruyucu statik
görüntüyü 45 saniyede bir sınırlı mesafede kaydırarak iz kalma riskini azaltmaya çalışır. Siyah-beyaz siyah
görünüm ışık kullanımını azaltmaya yardımcı olur, ancak açık ekran ısınmayı/pil tüketimini tamamen önleyemez. Daha serin
kullanım için ekran parlaklığını düşür; uygulama görünürken ekranın kapanması da Android ekran-açık isteği
nedeniyle engellenir.

Not: Hazır istasyonların yayın adresleri zaman içinde değişebilir; açılmayan bir kanal düzenlenebilir, silinebilir
veya varsayılanlar geri yüklenebilir. Ekran açık kalma yalnızca uygulama görünür durumdayken geçerlidir; telefon
açılışında uygulamayı otomatik başlatma desteklenmez.
