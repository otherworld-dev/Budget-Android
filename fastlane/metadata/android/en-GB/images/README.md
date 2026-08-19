# Store graphics — outstanding

This directory holds the visual assets for the Play and F-Droid listings. None
are checked in yet because producing them needs a rendered build (an emulator
or device running the app) and a design pass, neither of which is part of
packaging. Before submitting to either store, add:

- `icon.png` — 512×512 PNG, the app icon. Can be exported from
  `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` /
  `app/src/main/res/drawable/ic_launcher_foreground.xml`.
- `featureGraphic.png` — 1024×500 PNG (Play Store only).
- `phoneScreenshots/1.png`, `2.png`, … — at least 2, ideally 4–8, showing
  Capture, Review, Quick Add (the manual "Add transaction" form), Recent,
  and Settings. PNG or JPEG, 16:9 or 9:16, 320–3840 px on the long edge
  (Play's limits; F-Droid is more lenient).

Delete this file once real assets land in its place.
