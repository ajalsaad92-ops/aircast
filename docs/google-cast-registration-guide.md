# Google Cast Developer Console — دليل التسجيل خطوة بخطوة (AirCast)

## معلومات جهازك الحالية (مأخوذة مباشرة من هاتفك Galaxy S24)

| البند | القيمة |
|---|---|
| عنوان IP على الشبكة المحلية | `192.168.102.111` |
| حالة استقبال Google Cast | مفعّل (`cast_on = true`) |
| معرّف التطبيق (App ID) الحالي | فارغ — سيستخدم المستقبِل الافتراضي `CC1AD845` |
| ملف الإعدادات | `/data/data/com.aircast.receiver.debug/shared_prefs/aircast.xml` |

بما أن App ID فارغ حاليًا، يعمل التطبيق الآن بالمستقبِل الافتراضي `CC1AD845`
(Styled Media Receiver). لتتمكن أجهزة الإرسال التي تعرف تطبيقك المخصص من
الوصول إليه، سجّل التطبيق في الكونسول ثم أدخل المعرّف كما يلي.

## الجزء الأول: التسجيل في Google Cast Developer Console

1. افتح في متصفحك (نفس حساب Google الموجود على هاتفك وجهاز Chromecast):

   https://cast.google.com/publish/

2. من اليسار اختر **Applications** ثم **New Receiver Application**.

3. اختر النوع: **Custom Receiver** (المستقبل الذي تستضيفه بنفسك).

4. املأ الحقول:

   | الحقل | القيمة |
   |---|---|
   | Name | `AirCast Receiver` |
   | Type | Custom Receiver (مختار تلقائيًا) |
   | Receiver Application URL | `https://192.168.102.111:8322/cast/receiver.html` |
   | Guest Mode | اتركه معطّلًا |
   | Google Cast for Audio | اتركه معطّلًا |
   | Android TV Application Details | فارغ |

   ملاحظة: رابط URL يُتحقق منه مرة واحدة فقط ضمن شبكتك المحلية أثناء
   التسجيل، والتشغيل الفعلي يعتمد على App ID فقط — لا حاجة لاستضافة خارجية.

5. اضغط **Save**. سيظهر لك **Application ID** بالصيغة:

   ```
   A1B2C3D4-E5F6-G7H8-I9J0-K1L2M3N4O5P6
   ```

   انسخه — سنُدخله في هاتفك مباشرة.

## الجزء الثاني: إدخال App ID في هاتفك (اختَر طريقة واحدة)

### الطريقة 1 — من واجهة التطبيق (الأسهل)

افتح التطبيق → Settings → Network → **معرف تطبيق Google Cast**
(أو `Google Cast app id`) → الصق المعرّف → احفظ.

### الطريقة 2 — مباشرة عبر ADB (بدون لمس التطبيق)

من PowerShell على جهازك (مع الهاتف متصل USB):

```powershell
adb shell "run-as com.aircast.receiver.debug sh -c 'sed -i \"s|</map>|<string name=\"castAppId\" value=\"YOUR-APP-ID\"/></map>|\" shared_prefs/aircast.xml'"
```

استبدل `YOUR-APP-ID` بالمعرّف المنسوخ، ثم أعد تشغيل التطبيق:

```powershell
adb shell am force-stop com.aircast.receiver.debug
adb shell am start -n com.aircast.receiver.debug/com.aircast.receiver.MainActivity
```

التحقق من النجاح:

```powershell
adb shell run-as com.aircast.receiver.debug cat shared_prefs/aircast.xml
```

## الجزء الثالث: انتظار التفعيل

بعد الحفظ يبقى التطبيق في حالة **Pending review** نحو 15–30 دقيقة، وقد تصل
إلى ساعة قبل أن يظهر لأجهزة Chromecast. بعد التفعيل ستجد في شاشة الإعدادات
داخل التطبيق: `المستقبِل جاهز — appId: YOUR-APP-ID`.

## الجزء الرابع: اختبار البث

1. تأكد أن هاتفك (Galaxy S24) وجهاز Chromecast وجهاز الإرسال على نفس شبكة Wi-Fi (`192.168.102.x`).
2. من جهاز الإرسال (هاتفك أو كروم على حاسوبك): افتح YouTube أو BubbleUPnP أو كروم.
3. اضغط أيقونة Cast واختر جهاز Galaxy S24 من القائمة.
4. سيبدأ التشغيل عبر مشغّل ExoPlayer على هاتفك مع دعم الترجمة والبث العكسي.

## مشاكل شائعة وحلولها

| المشكلة | الحل |
|---|---|
| Chromecast لا يرى التطبيق بعد التفعيل | أعد تشغيل Chromecast وانتظر حتى ساعة (TTL تسجيل) |
| يظهر التطبيق لكن البث يفشل | تأكد أن "استقبال Google Cast" مفعّل وأن "حماية Google Cast" تسمح بالإرسال |
| شاشة سوداء عند البث من كروم | صفحة `receiver.html` تُخدم عبر منفذ TLS على هاتفك — تأكد أن التطبيق يعمل |
| الحساب مختلف | نفس حساب Google يجب أن يكون على جهاز الإرسال (للتطبيقات غير المنشورة رسميًا) |
