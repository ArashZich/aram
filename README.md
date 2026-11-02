<p align="center"><img src="assets/icon-512.png" width="128" alt="Hush"></p>

<h1 align="center">Hush — هیس</h1>

<p align="center">
  <b>One calm notification instead of a shade full of them.</b><br>
  A notification inbox for Android: every app's notifications, sorted and kept, inside one quiet app.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-2563eb" alt="MIT"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-2563eb" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/internet-none-2563eb" alt="No internet permission">
  <img src="https://img.shields.io/badge/dependencies-none-2563eb" alt="No dependencies">
</p>

---

Android shows notifications one by one: they float over what you are doing, pile up in the shade and buzz all day.
Hush takes them out of the shade as they arrive, keeps them in its own inbox, sorted by app, and leaves a single
silent line in the shade: *“12 notifications · Telegram 8 · Gmail 3 · +2”*. The few things that really matter still
reach you right away.

[فارسی ↓](#فارسی)

## Features

**Inbox**
- Every notification goes into Hush, grouped by app, with the sender's photo, full text and time
- Tap opens it exactly where the app meant (the chat, the email); swipe to clear one, or clear an app or everything
- Chats are kept as one conversation: a new Telegram message updates its row instead of adding a new one
- **Copy code**: a verification code (OTP) gets its own chip — one tap copies it. Persian digits work too
- **Remind me later**: hold a notification → in 1 hour, this evening or tomorrow morning; it comes back with a sound
- **Reply**: messages that offer a reply in the shade (Telegram, WhatsApp, SMS…) get a *Reply* chip
- **History**: everything of the last days, searchable, also what you cleared (7, 30 or 90 days)
- **Stats**: a month calendar shaded by how busy each day was, that day's notifications, and the week's noisiest
  apps with a one-tap *Make silent*

**Per app**, four levels:

| Level | What happens |
|---|---|
| **Important** | Floats with Hush's calm card *and* is kept in the inbox |
| **Inbox** (default) | Goes quietly into the inbox and the summary |
| **Silent** | Goes into the inbox already read, not counted |
| **Shade** | Hush leaves it alone; Android shows it as usual |

**And**
- **People and words**: a name or a word always floats, even from an app you silenced
- **Quiet hours**: nothing floats at night except calls and alarms
- **No floods**: one app floats at most once a minute (or every 5 min, or every time)
- **Scheduled summary** (off by default): the shade's summary changes only at your times (10:00, 14:00, 19:00) or
  every 15, 30 or 60 minutes, silently; the inbox in the app stays live
- Quick Settings tile, English and Persian (RTL), light and dark, calm blue
- No internet permission, no Google services, no libraries — nothing leaves the phone

**Never touched**: calls, alarms, music, navigation and other ongoing notifications stay where Android puts them.

## How it works

Hush is a *notification listener*. When an app posts a notification, Hush saves it in a small SQLite database on the
phone and removes it from the shade, then updates its own low-importance summary notification (no badge, no sound).

Floating (heads-up) notifications are switched off with Android's global setting `heads_up_notifications_enabled`,
since no ordinary app can change another app's floating. For *Important* apps and your people and words, Hush draws
its own soft card over other apps instead.

Good to know:
- Each app keeps its own notification sound: Hush takes the notification out of the shade, but Android has already
  played the app's sound, as set in Android's settings
- A notification's tap action lives only in memory (Android can't store it). After a restart, tapping an old item
  opens the app, not the exact chat. The same goes for *Reply*: after a restart the chip is gone until the next message
- Turning Hush off — or *Settings → Turn off and uninstall* — puts the phone's own floating setting back

## Install

1. Download `Hush-<version>.apk` from [Releases](../../releases) and install it.
2. Give the one-time permissions over adb (Developer options → USB debugging; on Xiaomi/HyperOS also
   *USB debugging (Security settings)* and *Install via USB*):

   ```sh
   adb shell "p=ir.arashzich.hush; pm grant \$p android.permission.WRITE_SECURE_SETTINGS; pm grant \$p android.permission.POST_NOTIFICATIONS; appops set \$p SYSTEM_ALERT_WINDOW allow; appops set \$p 10008 allow; cmd notification allow_listener \$p/\$p.Listener"
   ```

   The same line is in the app's setup, with a Copy button. `10008` is Xiaomi's *Autostart*; without it HyperOS
   refuses to connect the listener (other phones just print an error for it, which is fine).
3. Open Hush, switch it on, and pick a level for the noisy apps.

**Xiaomi / HyperOS**: an update may take back *Display over other apps* and *Autostart* a few seconds after
installing. Hush shows the setup box again; run the line once more.

**Uninstalling**: use *Settings → Turn off and uninstall*. If Hush was removed another way:
`adb shell settings put global heads_up_notifications_enabled 1`

## Build

JDK 17 and the Android SDK are enough; there are no third-party dependencies.

```sh
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests of the rules (levels, inbox, codes, reminders)
./gradlew lintDebug
./release.sh                   # signed APK + AAB + SHA256SUMS in dist/ (needs keystore.properties)
```

`keystore.properties` (gitignored):

```properties
storeFile=keystore/hush-release.jks
storePassword=...
keyAlias=hush
keyPassword=...
```

### Code map

| File | What it does |
|---|---|
| `Rules.kt` | Pure decisions, unit-tested: float / keep / remove, what goes into the inbox, codes, reminder times |
| `Listener.kt` | The notification listener: reads, saves, removes, floats |
| `Db.kt` | The inbox and history (SQLite) |
| `Inbox.kt` | The summary notification, tap actions, sender photos |
| `Reminder.kt` | “Remind me later” alarms, also after a restart |
| `Popup.kt` | The floating card |
| `Gate.kt` | The global heads-up switch and permission checks |
| `MainActivity.kt`, `AppActivity.kt`, `*Page.kt`, `Rows.kt` | The screens |

## فارسی

**هیس** به جای یک کشوی پر از نوتیف، فقط **یک نوتیف آرام** نشان می‌دهد. نوتیف همه‌ی برنامه‌ها را برمی‌دارد، داخل
خودش برنامه به برنامه نگه می‌دارد و در کشوی نوتیف فقط یک خط بی‌صدا می‌گذارد. چیزهای واقعاً مهم همچنان همان لحظه
می‌رسند.

- صندوق نوتیف‌ها: هر برنامه جدا، با عکس فرستنده، متن کامل و زمان؛ زدن، دقیقاً همان چت یا ایمیل را باز می‌کند
- **کپی کد**: کد تأیید (رمز یکبار مصرف) یک دکمه‌ی جدا دارد، حتی با عددهای فارسی
- **بعداً یادم بنداز**: یک ساعت دیگر، امشب یا فردا صبح
- **جواب**: پیام‌هایی که در کشو دکمه‌ی جواب دارند (تلگرام، واتس‌اپ، پیامک…) داخل هیس هم «جواب» دارند
- **تاریخچه**: همه‌چیز چند روز اخیر، با جست‌وجو
- **آمار**: تقویم ماه با شلوغی هر روز، نوتیف‌های آن روز، و پرسروصداترین برنامه‌های هفته با «ساکتش کن»
- **خلاصه‌ی زمان‌بندی‌شده** (پیش‌فرض خاموش): خلاصه‌ی کشو فقط در ساعت‌های دلخواه یا هر ۱۵، ۳۰ یا ۶۰ دقیقه عوض می‌شود، بی‌صدا
- چهار سطح برای هر برنامه: **مهم** (شناور + صندوق)، **صندوق**، **ساکت** (خوانده‌شده)، **کشو** (دست نمی‌زند)
- آدم‌ها و کلمه‌های مهم همیشه شناور می‌شوند؛ ساعت سکوت؛ جلوگیری از رگبار نوتیف
- تماس، زنگ ساعت، موسیقی و مسیریابی دست‌نخورده می‌مانند
- بدون دسترسی اینترنت، بدون سرویس گوگل، بدون کتابخانه؛ هیچ چیز از گوشی بیرون نمی‌رود

نصب: APK را از [Releases](../../releases) بگیرید و یک بار دستور adb بالا را اجرا کنید (داخل برنامه با دکمه‌ی
کپی هست). برای حذف، از *تنظیمات ← خاموش کن و حذف کن* استفاده کنید تا تنظیم گوشی برگردد.

## License

[MIT](LICENSE) © Arash Zich
