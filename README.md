# فيها فلوس (Feiha Flous)

تطبيق أندرويد لأماكن ماكينات ATM القريبة وبلاغات حالة الكاش (فاضية / عطلانه / اتملت).

## البناء
1. ثبّت JDK 17 أو أحدث.
2. ثبّت Android SDK command line tools، ومنها:
   `sdkmanager "platforms;android-34" "build-tools;34.0.0"`
3. `export ANDROID_SDK=/path/to/sdk` و `export JAVA_HOME=/path/to/jdk`
4. `./build.sh` — الناتج `feiha-flous.apk`.

ملحوظة: مفتاح التوقيع غير مضمّن في النسخة دي. أول تشغيل لـ build.sh هيولّد مفتاح جديد ليك. التحديث فوق نسخة متثبتة بمفتاح تاني هيتطلب إزالة النسخة القديمة الأول.

## البنية
- `app/AndroidManifest.xml` — الأذونات: INTERNET + الموقع. minSdk 26، targetSdk 34.
- `app/src/.../MainActivity.java` — غلاف WebView: جسر تخزين JSON محلي، GPS، منتقي ملفات (صور/JSON)، تصدير البيانات إلى Downloads، وجلب ماكينات OpenStreetMap من Overpass API.
- `app/assets/app.html` — الواجهة كلها (عربي RTL) بخريطة Leaflet مضمّنة محلياً في `app/assets/leaflet/`.
- الأيقونات SVG مضمّنة داخل HTML (مفيش اعتماد على خطوط إيموجي).

## ملاحظات
- البيانات محلية على الجهاز. المشاركة بين المستخدمين يدوياً: تصدير JSON من داخل التطبيق وإرساله، ثم استيراده عند الطرف التاني (دمج تلقائي بدون تكرار).
- الخريطة وجلب الماكينات محتاجين إنترنت (OpenStreetMap + Overpass API). راعِ سياسات الاستخدام العادل للخدمتين لو التطبيق اتنشر على نطاق واسع.

## التحديث من جوه التطبيق (v2.0.0)
التطبيق بيقرا ملف feiha-version.json من Release اسمه feiha على GitHub (الرابط في app/src/com/feihaflous/app/Updater.java). لو فيه versionCode أكبر بيظهر إشعار وزرار "حدّث دلوقتي".
لإصدار تحديث جديد: ارفع APK جديد باسم feiha-flous.apk وعدّل versionCode و versionName في feiha-version.json على نفس الـ Release.
لازم الـ APK يتوقع بنفس مفتاح التوقيع القديم، وإلا الأندرويد هيرفض التحديث فوق القديم.
