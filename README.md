# AirCast Receiver

**مستقبِل بث للشاشة والوسائط على أندرويد** — بديل مفتوح المصدر لتطبيق AirScreen، مبني على
Capacitor + React مع طبقة أصلية بلغة Kotlin تنفّذ البروتوكولات فعلياً.

> An open-source AirScreen-style receiver for Android phones, tablets and TV boxes.
> Capacitor + React shell, native Kotlin protocol stack.

---

## ١. ما الذي يفعله AirScreen؟ (تحليل)

AirScreen (‏`com.ionitech.airscreen` من Ionitech) يحوّل جهاز أندرويد — غالباً صندوق
Android TV أو Fire TV — إلى **جهاز مستقبِل** للبث اللاسلكي. لا يُثبَّت شيء على الجهاز
المرسِل؛ كل شيء موجود على المستقبِل.

| ما يقدّمه AirScreen | التفصيل |
| --- | --- |
| **AirPlay** | استقبال من iOS 8–18 و macOS: نسخ الشاشة، فيديو، صور، صوت |
| **Google Cast** | استقبال من تطبيقات Chromecast ومن متصفّح Chrome |
| **Miracast** | استقبال Wi-Fi Direct (معطّل عملياً منذ Android 8) |
| **DLNA / UPnP** | استقبال من أندرويد وويندوز وأي مشغّل يدعم UPnP |
| تسجيل الشاشة | حفظ ما يُعرض كملف فيديو |
| تسريع عتادي | فك ترميز H.264/HEVC عبر العتاد، دعم 4K |
| خدمة خلفية | يبقى ظاهراً على الشبكة والشاشة مطفأة |
| نقل مشفّر | حماية جلسة البث |
| نموذج مجاني + اشتراك | الاشتراك يزيل التنبيهات وبعض الحدود |
| قيد معلَن | لا يتجاوز حماية النسخ (Netflix وغيرها تظهر سوداء) |

**المصادر:** [Google Play](https://play.google.com/store/apps/details?id=com.ionitech.airscreen) ·
[Amazon Appstore](https://www.amazon.com/AirScreen-AirPlay-Google-Cast-Miracast/dp/B07CPZ698R) ·
[Softonic](https://airscreen-airplay-miracast.en.softonic.com/android) ·
[APKPure](https://apkpure.com/airscreen-airplay-cast/com.ionitech.airscreen)

---

## ٢. ماذا يقدّم AirCast مقابل ذلك؟

| الميزة | AirScreen | AirCast | ملاحظة |
| --- | :---: | :---: | --- |
| DLNA / UPnP MediaRenderer | ✅ | ✅ | تنفيذ كامل: SSDP + AVTransport + RenderingControl + ConnectionManager + GENA |
| تشغيل فيديو / صوت / صور | ✅ | ✅ | ExoPlayer مع HLS و DASH وتسريع عتادي و4K |
| AirPlay — فيديو وصور وتحكّم | ✅ | ✅ | إعلان mDNS + ‏`/play` و`/scrub` و`/rate` و`/photo` |
| AirPlay — نسخ الشاشة الكامل | ✅ | ❌ | يتطلّب تشفير FairPlay من آبل — انظر القسم ٣ |
| مشاركة الشاشة من حاسوب/جوال (نمط AirPin) | ✅ (عبر Miracast/Cast) | ✅ (عبر WebRTC) | يعمل من Chrome/Edge على ويندوز وماك ولينكس وأندرويد |
| Google Cast (مستقبِل) | ✅ | ✅ | تنفيذ Cast V2 كامل + Custom Receiver — انظر القسم ٣ والوثائق |
| حماية الاتصال (AirPlay/Cast) | ⚠️ | ✅ | رمز على الشاشة، كلمة مرور، قبول/رفض مع ثقة دائمة |
| اتصال متعدد الأجهزة بحدّ قابل للضبط | ⚠️ | ✅ | حدّ أقصى قابل للإعداد + تحذير فوري |
| متصفح وسائط الشبكة SMB/NAS | ⚠️ | ✅ | jcifs-ng (SMB2/3) مع تيار HTTP ودعم byte-range |
| ترجمة مخصصة (SRT/VTT/ASS) | ⚠️ | ✅ | رفع محلي وتحويل إلى WebVTT وإرفاقها بالفيديو |
| Miracast | ⚠️ معطّل منذ Android 8 | ❌ | لا واجهة برمجية عامة في أندرويد |
| تسجيل الشاشة | ✅ | ✅ | MediaProjection مع حفظ في معرض الفيديو |
| خدمة خلفية دائمة | ✅ | ✅ | Foreground Service + WakeLock + بدء تلقائي عند الإقلاع |
| واجهة عربية/إنجليزية | ❌ | ✅ | RTL كامل |
| دعم Android TV | ✅ | ✅ | LEANBACK_LAUNCHER + بانر + تحكّم بأزرار الريموت |
| رمز PIN للحماية | ⚠️ | ✅ | لمشاركة الشاشة |
| مفتوح المصدر ومجاني بالكامل | ❌ | ✅ | لا اشتراك ولا إعلانات |

---

## ٣. ما لم يُنفَّذ عمداً — والسبب


1. **استقبال Google Cast — لم يعد قيدًا.** نفّذ AirCast بروتوكول المستقبِل
   Cast V2 بنفسه (اكتشاف + `RECEIVER_QUERY` + جلسة بث + تحكم بالصوت) ويستقبل
   الآن من تطبيقات Cast على الشبكة المحلية. لتخصيص معرف التطبيق، سجّل
   **Custom Receiver** مجانًا في [Cast Developer Console](https://cast.google.com/publish/)
   وفق الدليل في [`docs/CAST_RECEIVER.md`](docs/CAST_RECEIVER.md) — لا حاجة إلى
   Google Cast SDK لأن المستقبِل يُستضاف على جهاز AirCast نفسه.
   البند الوحيد الذي يبقى غير قابل للتنفيذ هو **إرسال** بث إلى أجهزة Chromecast
   خارجية (دور Sender المعتمد على SDK المغلق) — لكن دور **المستقبِل** هو ما
   يماثل وظيفة AirScreen أصلًا.

2. **نسخ شاشة AirPlay من آبل**
   يعتمد على تبادل مفاتيح FairPlay (‏`/fp-setup`)، وتنفيذه يتطلّب شحن مفاتيح آبل
   المسرّبة داخل التطبيق. نردّ على `/fp-setup` بـ `501`.
   **البديل:** تشغيل الفيديو والصور والصوت عبر AirPlay يعمل بالكامل.

3. **Miracast**
   أندرويد لا يوفّر أي واجهة برمجية عامة لدور «المستقبِل» (Sink) منذ الإصدار 8،
   وهو ما يعترف به AirScreen نفسه في وصفه.
   **البديل:** مشاركة الشاشة عبر WebRTC تغطي نفس الحاجة من أي متصفّح حديث.

4. **المحتوى المحمي (DRM)**
   Netflix و Disney+ وغيرها تمنع النسخ على مستوى النظام. أي مستقبِل — بما فيه
   AirScreen — يعرض شاشة سوداء.

---

## ٤. لماذا Capacitor + طبقة أصلية؟

طلب المستخدم إضافة Capacitor. لكن WebView لا يستطيع إطلاقاً استقبال AirPlay أو DLNA:
هذه بروتوكولات تحتاج mDNS و SSDP على UDP multicast، وخوادم HTTP خام، وفكّ ترميز
عبر MediaCodec. لذلك:

```
┌─────────────────────────────────────────────────────────┐
│  React + TypeScript  (الواجهة، داخل WebView)            │
│  الحالة · الإعدادات · السجل · طرف WebRTC المستقبِل      │
└───────────────▲─────────────────────────────────────────┘
                │  AirCastPlugin (جسر Capacitor)
┌───────────────┴─────────────────────────────────────────┐
│  Kotlin — ReceiverService (Foreground Service)          │
│                                                         │
│  HttpServer(8321)  ─ DLNA · صفحة الإرسال · إشارات WebRTC│
│  HttpServer(8322)  ─ TLS ذاتي التوقيع (سياق آمن)        │
│  HttpServer(7000)  ─ AirPlay                            │
│  Ssdp(1900/UDP)    ─ اكتشاف UPnP + MulticastLock        │
│  NsdAdvertiser     ─ ‎_airplay._tcp · _raop._tcp        │
│  PlayerActivity    ─ ExoPlayer (فيديو/صوت/صور)          │
│  RecorderService   ─ MediaProjection                    │
└─────────────────────────────────────────────────────────┘
```

**لماذا نوع الخدمة `specialUse` وليس `mediaPlayback`؟** التطبيقات التي تستهدف
Android 15 فأعلى ممنوعة من تشغيل خدمة مقدّمة من نوع `mediaPlayback` انطلاقاً من
`BOOT_COMPLETED`؛ النظام يرمي `ForegroundServiceStartNotAllowedException`، وكان
«التشغيل التلقائي عند الإقلاع» سيفشل صامتاً على صناديق التلفاز الحديثة تحديداً.
الخدمة هنا تحتفظ بالمنافذ والاكتشاف ولا تشغّل الوسائط بنفسها (‏`PlayerActivity`
هي من تفعل)، لذا `specialUse` هو التوصيف الصحيح والمسموح. يبقى `mediaPlayback`
معلَناً للإصدارات 29–33 حيث لا وجود لـ `specialUse` بعد.

**لماذا شهادة TLS تُولَّد وقت التشغيل؟** واجهة `getDisplayMedia()` — التي تلتقط شاشة
الجهاز المرسِل — محجوبة خارج «السياق الآمن». صفحة على `http://192.168.1.x` ليست
سياقاً آمناً، لذا نولّد شهادة موقّعة ذاتياً تحمل عنوان الجهاز في حقل SAN ونقدّم صفحة
المشاركة عبر HTTPS. المتصفّح يعرض تحذيراً مرة واحدة فقط.

**لماذا تُستبدل أسماء `.local` في SDP؟** Chromium يخفي عناوين الشبكة المحلية خلف
أسماء mDNS عشوائية، والطرف الآخر غالباً لا يستطيع تحليلها فيفشل الاتصال بصمت.
نستبدلها بعنوان الجهاز الحقيقي الذي نعرفه أصلاً (`useMirror.ts`).

---

## ٥. البناء

### المتطلبات

| الأداة | الإصدار |
| --- | --- |
| Node.js | 20 أو أحدث |
| JDK | **21** (إلزامي — إضافات Capacitor 8 تطلبه) |
| Android SDK | Platform 36 · Build-Tools 35 |

### محلياً

```bash
npm install
npm run build          # فحص الأنواع + حزم الويب
npx cap sync android
cd android && ./gradlew :app:assembleDebug
```

الناتج: `android/app/build/outputs/apk/debug/app-debug.apk`

على ويندوز، إن لم يكن JDK 21 هو الافتراضي:

```powershell
$env:JAVA_HOME="C:\Users\<you>\.jdks\jdk-21.0.12+8"
cd android; .\gradlew.bat :app:assembleDebug
```

### معاينة الواجهة في المتصفّح

```bash
npm run dev
```

يعمل التطبيق ببيانات وهمية (‏`webFallback` في `src/lib/aircast.ts`) دون جهاز.

### التوقيع للإصدار

أنشئ `android/keystore.properties` (مستثنى من Git):

```properties
storeFile=release.keystore
storePassword=...
keyAlias=aircast
keyPassword=...
```

ثم `./gradlew :app:bundleRelease`. بدون هذا الملف يُنتج البناء ملفاً غير موقّع بدل أن يفشل.

---

## ٦. النشر التلقائي على GitHub

الملف `.github/workflows/android.yml` جاهز ويقوم بـ:

* بناء APK للتصحيح عند كل دفع وطلب دمج، ورفعه كـ artifact.
* عند دفع وسم `v*`: بناء APK و AAB موقّعين وإنشاء إصدار GitHub تلقائياً.

**الأسرار المطلوبة** (Settings → Secrets and variables → Actions) — اختيارية،
وبدونها يُنتج البناء ملفاً غير موقّع:

| السرّ | المحتوى |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | ‏`base64 -w0 release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | كلمة مرور المخزن |
| `ANDROID_KEY_ALIAS` | اسم المفتاح |
| `ANDROID_KEY_PASSWORD` | كلمة مرور المفتاح |

### رفع المشروع لأول مرة

المستودع مهيّأ محلياً بالكامل (‏`git init` + التزام أولي). لدفعه:

```bash
cd C:/AirCast
gh repo create aircast-receiver --public --source=. --push
# أو يدوياً:
git remote add origin https://github.com/<user>/aircast-receiver.git
git push -u origin main
```

ثم `git tag v1.0.0 && git push --tags` لإطلاق أول إصدار.

---

## ٧. قائمة اختبار على جهاز حقيقي

الأمور التي لا يمكن التحقّق منها إلا بجهازين على شبكة واحدة:

* [ ] **DLNA** — من معرض صور أندرويد أو VLC: هل يظهر اسم الجهاز؟ هل يعمل التشغيل والإيقاف والتقديم؟
* [ ] **DLNA من ويندوز** — نقر يمين على ملف فيديو → Cast to Device.
* [ ] **GENA** — هل يتحرّك شريط التقدّم في تطبيق المرسِل أثناء التشغيل؟
* [ ] **AirPlay** — من آيفون: هل يظهر الجهاز في مركز التحكّم؟ هل يعمل زر AirPlay داخل تطبيق فيديو؟
* [ ] **المشاركة** — فتح `https://<ip>:8322/cast` في Chrome، قبول تحذير الشهادة، ثم بدء المشاركة.
* [ ] **تغيّر العنوان** — قطع الواي فاي وإعادة الاتصال: هل يعود الجهاز للظهور خلال دقيقة؟
* [ ] **الخلفية** — إطفاء الشاشة 10 دقائق ثم محاولة الإرسال.
* [ ] **التسجيل** — بدء التسجيل ثم إيقافه والتحقّق من ظهور الملف في معرض الفيديو.
* [ ] **Android TV** — التنقّل بالريموت فقط: هل كل عنصر قابل للوصول؟

## ٨. حل المشكلات

| العرض | السبب الغالب |
| --- | --- |
| الجهاز لا يظهر في قائمة DLNA | الجهازان على شبكتين مختلفتين، أو الراوتر يعزل العملاء (AP isolation) |
| يظهر ثم يختفي | خدمة المقدّمة قُتلت — فعّل «منع السكون» واستثنِ التطبيق من تحسين البطارية |
| المشاركة لا تبدأ | فُتحت الصفحة عبر `http` بدل `https`، أو رُفض تحذير الشهادة |
| المشاركة تعلق على «جارٍ الاتصال» | جدار حماية على المرسِل يحجب UDP داخل الشبكة المحلية |
| فيديو أسود مع صوت فقط | محتوى محمي بـ DRM |
| AirPlay يظهر لكن نسخ الشاشة يفشل | متوقّع — القسم ٣، البند ٢ |

---

## ٩. الترخيص والامتثال

الكود مكتوب من الصفر اعتماداً على مواصفات مفتوحة (UPnP AV 1.0، DLNA DMR-1.50،
SSDP، DNS-SD، WebRTC). لا يحتوي المشروع على أي مفاتيح أو شيفرة مملوكة لآبل أو Google،
ولا يتجاوز أي حماية نسخ. الأسماء AirPlay و Google Cast و Miracast و DLNA علامات
تجارية لمالكيها، وتُذكر هنا للتوصيف فقط.

---

## ١٠. نظارات Meta Quest

Quest **لا ترسل** عبر AirPlay ولا DLNA ولا Miracast. وجهتاها الوحيدتان:

1. **جهاز Chromecast معتمَد** — استقباله يتطلّب شهادة جهاز صادرة من Google، ولا يمكن
   لتطبيق مستقل الحصول عليها.
2. **صفحة ويب** — `oculus.com/casting`.

لذلك أضاف AirCast الخيار الثاني بدل أن يدّعي الأول: زر **«فتح شاشة استقبال Quest»**
في تبويب «الشاشة» يفتح تلك الصفحة بملء الشاشة داخل التطبيق نفسه
(`cast/CastWebActivity.kt`)، بمتصفّح منفصل عن جسر Capacitor حتى لا تصل الصفحة البعيدة
إلى واجهة الإضافات.

من النظارة: **الكاميرا ← بث ← الحاسوب**.

يحتاج تسجيل دخول بحساب Meta واتصال إنترنت لبدء الجلسة؛ الفيديو نفسه يمرّ بعدها عبر
الشبكة المحلية.

### لماذا لا يصلح miraclecast هنا

[miraclecast](https://github.com/albfan/miraclecast) يعمل على **لينكس** لا أندرويد،
ويحتاج تحكّماً مباشراً بـ `wpa_supplicant` عبر D-Bus وصلاحيات root — وهذا بالضبط ما
يمنعه أندرويد عن التطبيقات (‏`setWfdInfo` محجوب خلف إذن `CONFIGURE_WIFI_DISPLAY`
من مستوى signature). وحتى لو عمل، فلن ينفع: **Quest لا تبثّ Miracast أصلاً.**

## ١١. عرض ملء الشاشة

عند بدء أي مشاركة شاشة، ينتقل العرض تلقائياً إلى ملء الشاشة (`.fullstage`) — بلا
أشرطة ولا تبويبات. لمسة واحدة أو زر Back للخروج. تشغيل DLNA و AirPlay يعمل بملء
الشاشة أصلاً عبر `PlayerActivity`.

خيار **«تلقائي»** في الجودة يعني: لا قيد على الارتفاع، فيسلّم المتصفّح الدقّة الأصلية
للشاشة. (تمرير `{ ideal: 0 }` كان سيطلب من Chromium مساراً بصفر بكسل فيردّ بأصغر حجم
ممكن — عكس المقصود تماماً.)

## ١٢. قيد الإنجاز
`mirror/Acmp.kt` و `mirror/MirrorSession.kt` هما نصف بروتوكول بثّ أصلي (التقاط
MediaProjection ← H.264 ← TCP ← فكّ ترميز عتادي) يهدف إلى بثّ شاشة هاتف أندرويد إلى
المستقبِل بلا متصفّح (نواة ميزة «AirPin»). الملفّان يُترجمان لكنهما **غير موصولين
بعد** بأي واجهة — الطرف المرسِل وخدمة الاستقبال لم يُكتبا. لا يؤثّران على أي
ميزة تعمل حالياً.

---

## ١٣. وثائق إضافية

| الوثيقة | المحتوى |
| --- | --- |
| [`docs/google-cast-setup.md`](docs/google-cast-setup.md) | دليل إنجليزي لتسجيل Custom Receiver خطوة بخطوة |
| [`docs/CAST_RECEIVER.md`](docs/CAST_RECEIVER.md) | الدليل العربي نفسه مع أسئلة شائعة وأساليب التحقّق |

---

*آخر تحديث: أغسطس 2026 — المراحل أ–ز مكتملة: أمان الاتصال، الاتصال متعدد الأجهزة،
التشخيص، الخدمة الخلفية وإعدادات العرض، متصفح SMB/الترجمة/Google Cast.*
