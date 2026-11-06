<p align="center"><img src="assets/icon-512.png" width="128" alt="Aram"></p>

<h1 align="center">Aram — آرام</h1>

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
Aram takes them out of the shade as they arrive, keeps them in its own inbox, sorted by app, and leaves one silent
summary in the shade that lists the latest apps. The few things that really matter still reach you right away.

[فارسی ↓](#فارسی)

## Features

**Inbox**
- Every notification goes into Aram, grouped by app, with the sender's photo, full text and time
- Tap opens it exactly where the app meant (the chat, the email); swipe to clear one, or clear an app or everything
- Chats are kept as one conversation: a new Telegram message updates its row instead of adding a new one
- **Copy code**: a verification code (OTP) gets its own chip — one tap copies it. Persian digits work too
- **Remind me later**: hold a notification → in 1 hour, this evening or tomorrow morning; it comes back with a sound
- **Reply**: messages that offer a reply in the shade (Telegram, WhatsApp, SMS…) get a *Reply* chip
- **History**: everything of the last days by day and app, also what you cleared; search and app chips show flat results (7, 30 or 90 days)
- **Stats**: a month calendar shaded by how busy each day was, the apps of the day you pick, and the week's noisiest
  apps with a one-tap *Make silent*
- **Opening an app** marks its items read; they stay in the inbox until you clear them

**Per app**, four levels:

| Level | What happens |
|---|---|
| **Important** | Floats with Aram's calm card *and* is kept in the inbox |
| **Inbox** (default) | Goes quietly into the inbox and the summary |
| **Silent** | Goes into the inbox already read, not counted |
| **Shade** | Aram leaves it alone; Android shows it as usual |

**And**
- **People and words**: a name or a word always floats, even from an app you silenced
- **Quiet hours**: nothing floats at night except calls and alarms
- **No floods**: one app floats at most once a minute (or every 5 min, or every time)
- **Scheduled summary** (off by default): the shade's summary changes only at your times (10:00, 14:00, 19:00) or
  every 15, 30 or 60 minutes, silently; the inbox in the app stays live
- **Language**: phone, فارسی or English in Settings, right-to-left in Persian, with Persian dates
- **Backup**: export and import settings, inbox and history as one file in *Settings → Inbox and history*
- Quick Settings tile, light and dark, calm blue
- No internet permission, no Google services, no libraries — nothing leaves the phone

**Never touched**: calls, alarms, music, navigation and other ongoing notifications stay where Android puts them.

## How it works

Aram is a *notification listener*. When an app posts a notification, Aram saves it in a small SQLite database on the
phone and removes it from the shade, then updates its own low-importance summary notification (no badge, no sound).

Floating (heads-up) notifications are switched off with Android's global setting `heads_up_notifications_enabled`,
since no ordinary app can change another app's floating. For *Important* apps and your people and words, Aram draws
its own soft card over other apps instead.

Good to know:
- Each app keeps its own notification sound, as set in Android's settings: Aram leaves a new notification in the shade
  for 4 seconds so the sound plays to the end, then takes it (at once and silently in quiet hours, for Silent apps
  and while the scheduled summary is on)
- A notification's tap action lives only in memory (Android can't store it). After a restart, tapping an old item
  opens the app, not the exact chat. The same goes for *Reply*: after a restart the chip is gone until the next message
- Turning Aram off — or *Settings → Turn off and uninstall* — puts the phone's own floating setting back

## Install

1. Download `Aram-<version>-universal.apk` from [Releases](../../releases) and install it.
2. Give the one-time permissions over adb (Developer options → USB debugging; on Xiaomi/HyperOS also
   *USB debugging (Security settings)* and *Install via USB*):

   ```sh
   adb shell "p=ir.arashzich.aram; pm grant \$p android.permission.WRITE_SECURE_SETTINGS; pm grant \$p android.permission.POST_NOTIFICATIONS; appops set \$p SYSTEM_ALERT_WINDOW allow; appops set \$p GET_USAGE_STATS allow; appops set \$p 10008 allow; cmd notification allow_listener \$p/\$p.Listener"
   ```

   The same line is in the app's setup, with a Copy button. `10008` is Xiaomi's *Autostart*; without it HyperOS
   refuses to connect the listener (other phones just print an error for it, which is fine).
3. Open Aram, switch it on, and pick a level for the noisy apps.

**Xiaomi / HyperOS**: an update may take back *Display over other apps* and *Autostart* a few seconds after
installing. Aram shows the setup box again; run the line once more.

The freezer of Xiaomi phones can stop background apps for minutes. So Aram's summary is a foreground service and stays in
the shade while Aram is on, as *Aram is on* when nothing is unread. Also set *Battery saver* to *No restrictions* for Aram.

**Coming from Hush**: Aram is the new name of [Hush](https://github.com/ArashZich/hush) and installs as a new app.
In Hush, export from *Settings → Inbox and history* and turn it off. Then give Aram its permissions, import the file
in the same place and turn Aram on. Pictures are not part of a backup. Remove Hush the normal way, while it is off.

**Uninstalling**: use *Settings → Turn off and uninstall*. If Aram was removed another way:
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
storeFile=keystore/aram-release.jks
storePassword=...
keyAlias=aram
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
| `Backup.kt` | Export and import; the file's kind stays `hush-backup` so a Hush backup still opens |
| `Lang.kt` | The app's language setting |
| `MainActivity.kt`, `AppActivity.kt`, `*Page.kt`, `Rows.kt` | The screens |

## فارسی

**آرام** به جای یک کشوی پر از نوتیف، فقط **یک نوتیف آرام** نشان می‌دهد. نوتیف همه‌ی برنامه‌ها را برمی‌دارد، داخل
خودش برنامه به برنامه نگه می‌دارد و در کشوی نوتیف فقط یک خط بی‌صدا می‌گذارد. چیزهای واقعاً مهم همچنان همان لحظه
می‌رسند.

- صندوق نوتیف‌ها: هر برنامه جدا، با عکس فرستنده، متن کامل و زمان؛ زدن، دقیقاً همان چت یا ایمیل را باز می‌کند
- **کپی کد**: کد تأیید (رمز یکبار مصرف) یک دکمه‌ی جدا دارد، حتی با عددهای فارسی
- **بعداً یادم بنداز**: یک ساعت دیگر، امشب یا فردا صبح
- **جواب**: پیام‌هایی که در کشو دکمه‌ی جواب دارند (تلگرام، واتس‌اپ، پیامک…) داخل آرام هم «جواب» دارند
- **تاریخچه**: همه‌چیز چند روز اخیر بر اساس روز و برنامه، با جست‌وجو
- **آمار**: تقویم ماه با شلوغی هر روز، برنامه‌های آن روز، و پرسروصداترین برنامه‌های هفته با «ساکتش کن»
- **خلاصه‌ی زمان‌بندی‌شده** (پیش‌فرض خاموش): خلاصه‌ی کشو فقط در ساعت‌های دلخواه یا هر ۱۵، ۳۰ یا ۶۰ دقیقه عوض می‌شود، بی‌صدا
- چهار سطح برای هر برنامه: **مهم** (شناور + صندوق)، **صندوق**، **ساکت** (خوانده‌شده)، **کشو** (دست نمی‌زند)
- آدم‌ها و کلمه‌های مهم همیشه شناور می‌شوند؛ ساعت سکوت؛ جلوگیری از رگبار نوتیف
- **زبان**: زبان گوشی، فارسی یا انگلیسی از تنظیمات، با راست‌چین کامل و تاریخ شمسی
- **پشتیبان**: خروجی و ورودی تنظیمات، صندوق و تاریخچه در یک فایل، از *تنظیمات ← صندوق و تاریخچه*
- باز کردن یک برنامه نوتیف‌هایش را خوانده می‌کند و تا وقتی پاکشان نکنید در صندوق می‌مانند
- تماس، زنگ ساعت، موسیقی و مسیریابی دست‌نخورده می‌مانند
- بدون دسترسی اینترنت، بدون سرویس گوگل، بدون کتابخانه؛ هیچ چیز از گوشی بیرون نمی‌رود

نصب: APK را از [Releases](../../releases) بگیرید و یک بار دستور adb بالا را اجرا کنید (داخل برنامه با دکمه‌ی
کپی هست). برای حذف، از *تنظیمات ← خاموش کن و حذف کن* استفاده کنید تا تنظیم گوشی برگردد.

**آمدن از هیس**: آرام نام تازه‌ی هیس است و به‌صورت برنامه‌ی جدید نصب می‌شود. در هیس از *تنظیمات ← صندوق و تاریخچه* خروجی بگیرید
و آن را خاموش کنید. بعد دسترسی‌های آرام را بدهید، فایل را از همان‌جا وارد کنید و آرام را روشن کنید. عکس‌ها در پشتیبان نیستند.

## License

[MIT](LICENSE) © Arash Zich
