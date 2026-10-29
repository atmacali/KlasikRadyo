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
- Yeni istasyon kaydedilince seçilip doğrudan çalmaya başlar; aktif kanal silinirse çalma durur ve sonraki kanal seçili kalır
- İstasyon ekleme, durdurma ve varsayılanları geri yükleme komutları **Menü** içinde
- “AMOLED EKRAN KORUYUCU” düğmesiyle saat, kanal adı ve iki çevirmeli kontrol dışında kalan arayüzü gizleme; çıkmak için kanal adına dokunma
- Kanal düğmesini 90° çevirince bir önceki/sonraki kanala geçme; her adımda açı sayacını sıfırlama ve dokunmayı tıklamadan ayırma
- Menüde **Kanal sırasını düzenle** seçilince kanalları erişilebilir yukarı/aşağı oklarıyla taşıma; sıra cihazda saklanır (favoriler kendi gruplarında kalır)
- Telefon yatay çevrildiğinde daha büyük ve aralıklı ses/kanal düğmeleri
- **Menü** içinden şarja takılınca radyo başlatma, şarjdan çıkarınca durdurma, Bluetooth otomatik çalma ve şarja takılınca AMOLED ekran koruyucu seçeneklerini ayrı ayrı açıp kapatma
- Menüden GitHub'daki ortak `stations.json` kanal kataloğunu elle güncelleme; silinen katalog kanallarını gizli listeden isteğe bağlı geri etkinleştirme
- Ses düğmesinde seçilen son ses seviyesini uygulama açılışları ve servis yeniden başlatmaları arasında koruma
- Favori işaretleme (kanal düzenlenirken korunur)
- Bluetooth kulaklık/medya kontrolleriyle oynat/duraklat ve ileri/geri kanal değiştirme
- Uygulama ilk açıldığında başlayan, sürekli bildirim göstererek şarj bağlantısını izleyen foreground service
- Varsayılan olarak şarja takılınca son seçili istasyonu başlatma; fiş çekilince yayını durdurup ekranı kapatma, izleme servisini sürdürme (menüden ayrı ayrı ayarlanabilir)
- Bluetooth kulaklık/cihaz bağlanınca son seçili radyoyu otomatik çalma (menüden açılır; Android 12+ Bluetooth izni gerekir)
- Siyah-beyaz arayüz; AMOLED koruyucuda yalnızca saat, istasyon adı ve ince beyaz tik çizgili (analog saat yüzü gibi) iki döner kontrol görünür. Görüntü 45 saniyede bir en fazla 10 piksel kayar.
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

Yeni özel istasyon eklerken çıkan onay penceresinde **Tamam** seçilirse, istasyon ilk kez gerçekten
çalarken adı ve yayın adresi GitHub deposundaki herkese açık
[`data/station-submissions.txt`](https://github.com/atmacali/KlasikRadyo/blob/main/data/station-submissions.txt)
inceleme dosyasına eklenir; bu bir issue açmaz. **İptal** seçilirse istasyon yalnızca bu telefonda kalır.
Uygulamada API ayarı veya gizli anahtar bulunmaz; istek HTTPS üzerinden Worker'a gider.
Aynı yayın adresi dosyada varsa tekrar eklenmez. Başarısız gönderim radyo çalmayı etkilemez; durum
alanında hata gösterilir ve istasyon daha sonra yeniden çalındığında tekrar denenir.

### İstasyon gönderim Worker'ını kurma/güncelleme

Worker kaynak kodu `worker/` klasöründedir. Kullanıcıların gönderdiği adlar ve URL'ler herkese açık
depo dosyasına yazılır; dosyayı GitHub'dan inceleyip uygun gördüklerinizi `stations.json` içine
elle ekleyin. Worker GitHub'da otomatik kanal değişikliği yapmaz.

1. GitHub **Settings > Developer settings > Fine-grained personal access tokens** üzerinden yeni token
   oluşturun. Repository access yalnızca `atmacali/KlasikRadyo`, izin ise **Contents: Read and write**
   olmalı. Android uygulamasına token koymayın.
2. Bilgisayarda bu depoyu klonlayın/açın ve terminalde `worker` klasörüne geçin:
   `cd worker`
3. Cloudflare hesabına giriş yapın: `npx wrangler@latest login`
4. Worker secret'ını ayarlayın/güncelleyin:
   `npx wrangler@latest secret put GITHUB_TOKEN`
   Komut sorunca yeni fine-grained token'ı terminale yapıştırın; repoya veya sohbete eklemeyin.
5. Kaynak kodunu aynı Worker'a dağıtın: `npx wrangler@latest deploy`. `wrangler.toml` mevcut
   `retroradyo-station-suggestions` Worker adını kullanır; dağıtımdan sonra uygulamanın mevcut
   `/submit` adresi aynı kalmalıdır. `/health` yanıtında `"version":"file-v1"` görünmelidir; görünmüyorsa Cloudflare'de hâlâ eski (issue tabanlı) kod çalışıyordur, yeniden deploy edin.
6. GitHub'daki `data/station-submissions.txt` dosyasını kontrol edin. Eski Worker token'ını artık
   kullanmayın; güvenlik için GitHub'dan iptal edin.

Worker GitHub Contents API ile dosyanın güncel SHA'sını alır ve yeni satırı ekler; eşzamanlı
değişikliklerde çakışmayı yeniden okuyup sınırlı sayıda dener. Gönderim başına alan doğrulaması,
istek boyutu sınırı ve Worker belleğiyle saatlik IP limiti uygulanır; bu son limit global/kalıcı
değil, Cloudflare Worker örneği başına en iyi çaba korumasıdır. Android yalnızca kullanıcı onay
verdiği ve yeni istasyon gerçekten çalmaya başladığı zaman gönderir; tekrarlayan denemeler aynı URL'yi
çoğaltmaz.

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
