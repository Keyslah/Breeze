# Before Bluetooth recovery and recorded-night notes

This folder preserves the pre-change Android source (`app-src`), previous installed-build APK, and a consistent phone-history database copied while only the phone app was stopped. The ESP32 continued recording independently.

`breeze-phone-before-feelings-v3.db` passed SQLite quick_check. It has schema version 2, 5 sessions, 38,104 sensor samples, and 26 weather records. SHA-256: `EEC9CD947706EB93DF488CF309205D2522A0A43CCB94E26A5DB50C8526F55F4E`.

The new app migrates the live database additively to schema 3 for dated Hot/Cold entries. This backup predates that migration and any readings received afterward; it must not be copied over newer live history without an explicit recovery decision.
