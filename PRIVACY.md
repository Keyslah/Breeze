# Breeze Privacy Policy

Effective date: November 1, 2026

Breeze is an independently maintained Android application for personal overnight
recording and historical monitoring. Its Android package is `com.aaron.breeze`.
The project is maintained by [Keyslah](https://github.com/Keyslah) at
[github.com/Keyslah/Breeze](https://github.com/Keyslah/Breeze).

This policy covers Breeze's existing features and the optional Oura connection
being developed for its History page. **The Oura connection is not yet available
in the current application.** The Oura section below describes the intended
collection and use when that connection becomes available and you authorize it.

## Information stored on your device

Breeze stores its recordings and preferences in private Android app storage.
Depending on the features you use, this includes:

- Temperature and humidity readings, recording times, sensor and controller
  identifiers, fan-setting history, and connection or synchronization status.
- Feeling entries you enter or dictate, including their times and ratings.
- Your settings, named control profiles, graph preferences, and selected weather
  location.
- Optional Dreo fan reports, temperature readings, and local diagnostic records.

The maintainer does not operate a server that receives your Breeze recording
history. Breeze does not include advertising or third-party analytics SDKs and
does not automatically upload its history or diagnostic logs to the maintainer.

## Optional Oura connection

The planned connection uses the official Oura API V2, after you explicitly
authorize Breeze through Oura's OAuth sign-in and consent flow. Breeze will not
ask for or store your Oura password or connect directly to your ring.

Its purpose is to display these four measurements alongside existing overnight
recordings:

1. Nightly skin-temperature deviation from your personal baseline.
2. Overnight heart-rate samples.
3. Sleep-period average respiratory rate.
4. Sleep stages.

The connection will request the `daily` permission needed for sleep and
readiness records. Oura's permission covers more fields than Breeze needs;
Breeze's import will retain only the four measurements above and the metadata
needed to identify, align, and update them, such as source record IDs, timestamps,
time-zone offsets, sample intervals, units, and import status. Unneeded response
fields will not be retained as additional features.

Oura authorization tokens will be protected on the device using Android
Keystore-backed encryption. Imported history will be stored separately from
sensor rows in private app storage and remain available offline. It will be
used only for historical display, not predictions, correlations,
recommendations, advertising, AI model training, or fan-control decisions.
Breeze will not upload sensor history to Oura or send imported Oura readings to
Dreo, weather services, or a maintainer-operated server.

Requests will go directly from the device to Oura. Oura will receive the
authorization and API requests, including the requested date ranges and normal
network information such as your IP address. Oura's processing of your account
and original ring data remains governed by Oura's own privacy policy.

## Other optional connections

**Dreo:** If you connect a Dreo account, Breeze sends your email address and a
password-derived credential to Dreo over HTTPS to sign in and retrieve supported
fan information. It stores the email, password-derived credential, access token,
and selected device information encrypted with an Android Keystore key. The
password-derived credential remains sensitive authentication information.
Breeze can retain returned fan state, temperature, and synchronization history
locally. Disconnecting Dreo removes the saved account credential envelope; it
does not erase previously recorded history.

**Outdoor weather:** If you search for a place or fetch outdoor weather, Breeze
sends the search text, or your selected location's coordinates and the requested
date range, to Open-Meteo over HTTPS. It does not use GPS location permissions
for this feature. Weather requests do not include your sensor readings, feeling
entries, or Oura measurements.

These providers receive normal network information, including your IP address,
when your device contacts them. Their services are governed by their own terms
and privacy policies.

## Microphone and local diagnostics

If you enable voice commands and grant microphone permission, speech recognition
runs on the device using an offline model. Breeze does not send microphone audio
to a cloud speech service or save raw microphone recordings. Recognized commands
can create local feeling entries or perform the action you requested. Local
Android diagnostic logs can contain recognized command text and device status.
You can disable listening in Breeze or revoke its microphone permission in
Android settings.

## Sharing and exports

Breeze does not sell your data, use it for targeted advertising, or provide
recording history to data brokers. Network sharing is limited to the optional
service requests described above.

When you explicitly export existing Breeze history or feeling entries, Android
lets you choose the destination. A destination you choose may be a cloud storage
provider; that provider then handles the exported copy under its own policies.
Copies you export or explicitly provide for troubleshooting are separate from
the app's private storage. Do not post credentials or personal health recordings
in the public source repository or its issue tracker.

## Storage, retention, and your choices

Breeze retains local history to let you review saved nights; it does not apply
an automatic time-based expiry to those recordings. The app disables Android's
standard automatic app-data backup through its manifest. History databases are
not separately encrypted by Breeze; Android's app isolation and device security
protect private storage. Account credentials use the additional encryption
described above.

You can stop optional connections or revoke Android permissions. For the planned
Oura connection, withdrawing Breeze's authorization through Oura stops future
authorized access; it does not by itself erase a previously imported local copy.

Android's app-storage controls or uninstalling Breeze can remove its local app
data, including saved history, settings, and credentials. This removes all
Breeze app data, so preserve any records you want before choosing that action.
Exported copies, independently created development backups, data remaining on
your controller, and data held by Oura or Dreo are not removed by clearing
Breeze's local storage. Manage those copies with their respective holder or
service. No data is deleted merely by reading this policy.

## Policy updates and contact

The project website is hosted by GitHub Pages. It contains informational pages
and does not provide an Oura sign-in form, collect health readings, or use
analytics scripts, tracking cookies, or third-party font services. GitHub
receives ordinary web requests, including your IP address, when you visit the
site; its handling of that information is governed by the
[GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

Changes to this policy will be published at this same address with an updated
effective date. The repository's file history records prior versions.

For privacy questions, contact the maintainer through the
[Breeze issue tracker](https://github.com/Keyslah/Breeze/issues). Issues are public:
describe the question without including passwords, tokens, or personal readings.
If private details are necessary, first ask the maintainer to arrange a private
contact method. The maintainer cannot access or delete data stored only on your
phone remotely.
