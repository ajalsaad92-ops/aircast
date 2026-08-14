# Google Cast Developer Console — دليل التسجيل خطوة بخطوة (AirCast)

## معلومات جهازك الحالية (مأخوذة مباشرة من هاتفك Galaxy S24)

| البند | القيمة |
|---|---|
| عنوان IP على الشبكة المحلية | `192.168.102.111` |
| حالة استقبال Google Cast | مفعّل (`cast_on = true`) |
| معرّف التطبيق (App ID) | `02898B6E` — تم التسجيل في الكونسول (Unpublished) وتفعيله على الهاتف |
| حالة المستقبِل (`castStatus`) | `ready: true` — المستقبِل جاهز للربط |
| ملف الإعدادات | `/data/data/com.aircast.receiver.debug/shared_prefs/aircast.xml` |

تم إنشاء التطبيق بنجاح في Google Cast Developer Console (Unpublished — يعمل فقط
على الأجهزة المملوكة لنفس حساب Google). بعد التفعيل من الكونسول استغرق الأمر
نحو 15–30 دقيقة قبل أن يصبح متاحًا.

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

> ملاحظة: التطبيق يدعم الآن (من الإصدار المحدث) استقبال المعرّف عبر أمر
> الإطلاق مباشرة، وهذه هي الطريقة الموثوقة. تعديل ملف XML يدويًا لا يصمد لأن
> ذاكرة العملية (SharedPreferences cache) تعيد كتابة الملف الكامل فوق أي
> تعديل خارجي عند كل إعادة تشغيل.

من PowerShell على جهازك (مع الهاتف متصل USB) — استبدل `02898B6E` بمعرّفك:

```powershell
adb shell am force-stop com.aircast.receiver.debug
adb shell am start -n com.aircast.receiver.debug/com.aircast.receiver.MainActivity --es cast_app_id 02898B6E
```

يُثبّت الأمر المعرّف فورًا قبل أي كتابة أخرى، ثم يبدأ المستقبِل تلقائيًا.

التحقق من النجاح (عبر البلغن):

```powershell
adb shell am force-stop com.aircast.receiver.debug
# افتح التطبيق مجددًا، ثم من أدوات المطور:
Runtime.evaluate → window.Capacitor.Plugins.AirCast.castStatus()
# يجب أن يرجع: {"appId":"02898B6E","ready":true}
```

وللتحقق من ملف الإعدادات مباشرة:

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
