export type Lang = 'ar' | 'en';

/**
 * Two flat dictionaries instead of a library: the app has one screenful of copy per
 * page, no plurals beyond a count suffix, and shipping i18next into a WebView that
 * already has to boot fast is not worth 40 KB.
 */
const dict = {
  ar: {
    'app.name': 'AirCast',
    'app.tagline': 'مستقبِل بث للشاشة والوسائط',

    'nav.home': 'الرئيسية',
    'nav.mirror': 'الشاشة',
    'nav.activity': 'النشاط',
    'nav.guide': 'الدليل',
    'nav.settings': 'الإعدادات',

    'state.online': 'يعمل',
    'state.offline': 'متوقف',
    'state.starting': 'جارٍ التشغيل',
    'state.noNetwork': 'لا يوجد اتصال شبكة',
    'state.discoverable': 'ظاهر للأجهزة على الشبكة',
    'state.hidden': 'غير ظاهر — اضغط للتشغيل',

    'power.turnOn': 'تشغيل الاستقبال',
    'power.turnOff': 'إيقاف الاستقبال',

    'net.wifi': 'واي فاي',
    'net.ethernet': 'إيثرنت',
    'net.cellular': 'بيانات الجوال',
    'net.none': 'غير متصل',
    'net.other': 'شبكة أخرى',
    'net.unknown': 'غير معروف',
    'net.address': 'العنوان',
    'net.network': 'الشبكة',

    'proto.airplay': 'AirPlay',
    'proto.airplay.desc': 'من آيفون وآيباد وماك — فيديو وصور وصوت',
    'proto.dlna': 'DLNA / UPnP',
    'proto.dlna.desc': 'من أندرويد وويندوز و VLC وأي تطبيق يدعم الإرسال',
    'proto.mirror': 'مشاركة الشاشة',
    'proto.mirror.desc': 'من متصفّح Chrome أو Edge عبر الشبكة المحلية',
    'proto.on': 'مفعّل',
    'proto.off': 'معطّل',

    'sessions.title': 'الأجهزة المتصلة',
    'sessions.empty': 'لا يوجد جهاز متصل الآن',
    'sessions.since': 'منذ',

    'playback.title': 'قيد التشغيل',
    'playback.nothing': 'لا يوجد تشغيل',
    'playback.from': 'من',

    'mirror.title': 'شاشة مشتركة',
    'mirror.waiting': 'بانتظار جهاز يبدأ المشاركة',
    'mirror.howto': 'افتح هذا العنوان في متصفّح الجهاز الذي تريد مشاركته:',
    'mirror.live': 'يتم العرض الآن',
    'mirror.stop': 'إنهاء المشاركة',
    'mirror.disabled': 'مشاركة الشاشة معطّلة من الإعدادات',
    'mirror.tlsMissing': 'تعذّر تجهيز الاتصال الآمن — أعد تشغيل الاستقبال',
    'mirror.certNote':
      'سيعرض المتصفّح تحذيراً بشأن الشهادة أول مرة لأنها صادرة من هذا الجهاز نفسه. اختر «متابعة» للاستمرار.',
    'mirror.fullscreen': 'ملء الشاشة',

    'record.start': 'بدء التسجيل',
    'record.stop': 'إيقاف التسجيل',
    'record.recording': 'جارٍ التسجيل',
    'record.saved': 'تم الحفظ في معرض الفيديو',
    'record.failed': 'تعذّر التسجيل',

    'activity.title': 'سجل النشاط',
    'activity.clear': 'مسح',
    'activity.empty': 'لا يوجد نشاط بعد',

    'guide.title': 'كيف أرسل إلى هذا الجهاز؟',
    'guide.ios': 'آيفون / آيباد / ماك',
    'guide.ios.1': 'تأكّد أن الجهازين على نفس شبكة الواي فاي',
    'guide.ios.2': 'افتح مركز التحكّم واختر «نسخ الشاشة» أو زر AirPlay داخل التطبيق',
    'guide.ios.3': 'اختر «{name}» من القائمة',
    'guide.ios.note':
      'يعمل تشغيل الفيديو والصور والصوت. نسخ الشاشة الكامل من آبل يتطلّب تشفير FairPlay وهو غير مضمَّن.',
    'guide.android': 'أندرويد',
    'guide.android.1': 'من معرض الصور أو مشغّل الفيديو اختر «إرسال / Cast»',
    'guide.android.2': 'أو استخدم VLC أو BubbleUPnP ثم اختر «{name}»',
    'guide.android.3': 'لمشاركة الشاشة كاملة افتح رابط المشاركة في متصفّح Chrome',
    'guide.windows': 'ويندوز',
    'guide.windows.1': 'انقر بزر الفأرة الأيمن على ملف فيديو ثم «Cast to Device»',
    'guide.windows.2': 'أو افتح رابط المشاركة في Chrome / Edge لمشاركة الشاشة',
    'guide.browser': 'أي متصفّح',
    'guide.browser.1': 'افتح الرابط أدناه على الجهاز الذي تريد مشاركته',
    'guide.open': 'فتح الصفحة',
    'guide.copy': 'نسخ الرابط',
    'guide.copied': 'تم النسخ',

    'settings.title': 'الإعدادات',
    'settings.identity': 'هوية الجهاز',
    'settings.deviceName': 'اسم الجهاز',
    'settings.deviceName.hint': 'هذا هو الاسم الذي يظهر على الأجهزة المرسِلة',
    'settings.protocols': 'البروتوكولات',
    'settings.behaviour': 'السلوك',
    'settings.autoStart': 'التشغيل التلقائي عند الإقلاع',
    'settings.keepScreenOn': 'منع الجهاز من السكون أثناء الاستقبال',
    'settings.recordAudio': 'تسجيل الصوت مع الشاشة',
    'settings.security': 'الحماية',
    'settings.pin': 'رمز PIN لمشاركة الشاشة',
    'settings.pin.hint': 'اتركه فارغاً للسماح بدون رمز',
    'settings.quality': 'جودة المشاركة المفضّلة',
    'settings.ports': 'المنافذ',
    'settings.language': 'اللغة',
    'settings.about': 'حول',
    'settings.version': 'الإصدار',
    'settings.fingerprint': 'بصمة الشهادة',
    'settings.restart': 'إعادة تشغيل الاستقبال',
    'settings.saved': 'تم الحفظ',
    'settings.notSupported': 'غير مدعوم',
    'settings.notSupported.body':
      'استقبال Google Cast يتطلّب ترخيصاً واعتماداً من Google، و Miracast لا يوفّر أندرويد واجهة برمجية لاستقباله منذ الإصدار 8. التفاصيل في ملف README.',

    'common.on': 'تشغيل',
    'common.off': 'إيقاف',
    'common.close': 'إغلاق',
    'common.retry': 'إعادة المحاولة',
  },

  en: {
    'app.name': 'AirCast',
    'app.tagline': 'Screen and media receiver',

    'nav.home': 'Home',
    'nav.mirror': 'Screen',
    'nav.activity': 'Activity',
    'nav.guide': 'Guide',
    'nav.settings': 'Settings',

    'state.online': 'Online',
    'state.offline': 'Offline',
    'state.starting': 'Starting',
    'state.noNetwork': 'No network connection',
    'state.discoverable': 'Visible to devices on this network',
    'state.hidden': 'Not discoverable — tap to start',

    'power.turnOn': 'Start receiving',
    'power.turnOff': 'Stop receiving',

    'net.wifi': 'Wi-Fi',
    'net.ethernet': 'Ethernet',
    'net.cellular': 'Mobile data',
    'net.none': 'Disconnected',
    'net.other': 'Other network',
    'net.unknown': 'Unknown',
    'net.address': 'Address',
    'net.network': 'Network',

    'proto.airplay': 'AirPlay',
    'proto.airplay.desc': 'From iPhone, iPad and Mac — video, photos and audio',
    'proto.dlna': 'DLNA / UPnP',
    'proto.dlna.desc': 'From Android, Windows, VLC and anything that can cast',
    'proto.mirror': 'Screen sharing',
    'proto.mirror.desc': 'From Chrome or Edge over the local network',
    'proto.on': 'Enabled',
    'proto.off': 'Disabled',

    'sessions.title': 'Connected devices',
    'sessions.empty': 'Nothing connected right now',
    'sessions.since': 'since',

    'playback.title': 'Now playing',
    'playback.nothing': 'Nothing playing',
    'playback.from': 'from',

    'mirror.title': 'Shared screen',
    'mirror.waiting': 'Waiting for a device to start sharing',
    'mirror.howto': 'Open this address in the browser of the device you want to share:',
    'mirror.live': 'Live',
    'mirror.stop': 'End sharing',
    'mirror.disabled': 'Screen sharing is switched off in settings',
    'mirror.tlsMissing': 'Secure channel unavailable — restart the receiver',
    'mirror.certNote':
      'The browser warns about the certificate the first time because this device issued it itself. Choose “Proceed” to continue.',
    'mirror.fullscreen': 'Fullscreen',

    'record.start': 'Start recording',
    'record.stop': 'Stop recording',
    'record.recording': 'Recording',
    'record.saved': 'Saved to your video gallery',
    'record.failed': 'Recording failed',

    'activity.title': 'Activity log',
    'activity.clear': 'Clear',
    'activity.empty': 'No activity yet',

    'guide.title': 'How to cast to this device',
    'guide.ios': 'iPhone / iPad / Mac',
    'guide.ios.1': 'Make sure both devices are on the same Wi-Fi network',
    'guide.ios.2': 'Open Control Centre and pick Screen Mirroring, or the AirPlay button in an app',
    'guide.ios.3': 'Choose “{name}” from the list',
    'guide.ios.note':
      'Video, photo and audio playback work. Apple’s full screen mirroring needs FairPlay encryption, which is not bundled.',
    'guide.android': 'Android',
    'guide.android.1': 'In Gallery or a video player choose “Cast”',
    'guide.android.2': 'Or use VLC / BubbleUPnP and select “{name}”',
    'guide.android.3': 'For full screen sharing open the share link in Chrome',
    'guide.windows': 'Windows',
    'guide.windows.1': 'Right-click a video file, then “Cast to Device”',
    'guide.windows.2': 'Or open the share link in Chrome / Edge to share your screen',
    'guide.browser': 'Any browser',
    'guide.browser.1': 'Open the link below on the device you want to share',
    'guide.open': 'Open page',
    'guide.copy': 'Copy link',
    'guide.copied': 'Copied',

    'settings.title': 'Settings',
    'settings.identity': 'Device identity',
    'settings.deviceName': 'Device name',
    'settings.deviceName.hint': 'This is the name senders will see',
    'settings.protocols': 'Protocols',
    'settings.behaviour': 'Behaviour',
    'settings.autoStart': 'Start automatically on boot',
    'settings.keepScreenOn': 'Keep the device awake while receiving',
    'settings.recordAudio': 'Record audio with the screen',
    'settings.security': 'Security',
    'settings.pin': 'PIN for screen sharing',
    'settings.pin.hint': 'Leave empty to allow without a PIN',
    'settings.quality': 'Preferred sharing quality',
    'settings.ports': 'Ports',
    'settings.language': 'Language',
    'settings.about': 'About',
    'settings.version': 'Version',
    'settings.fingerprint': 'Certificate fingerprint',
    'settings.restart': 'Restart the receiver',
    'settings.saved': 'Saved',
    'settings.notSupported': 'Not supported',
    'settings.notSupported.body':
      'A Google Cast receiver requires Google certification and licensing, and Android has exposed no Miracast sink API since version 8. Details are in the README.',

    'common.on': 'On',
    'common.off': 'Off',
    'common.close': 'Close',
    'common.retry': 'Retry',
  },
} as const;

export type MessageKey = keyof (typeof dict)['ar'];

export function translate(lang: Lang, key: MessageKey, vars?: Record<string, string>): string {
  const table = dict[lang] as Record<string, string>;
  let out = table[key] ?? (dict.en as Record<string, string>)[key] ?? key;
  if (vars) {
    for (const [name, value] of Object.entries(vars)) {
      out = out.replace(`{${name}}`, value);
    }
  }
  return out;
}
