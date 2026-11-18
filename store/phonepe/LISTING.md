# PhonePe Appstore listing: Ruta

Copy-paste source for developer.phonepeappstore.com. Keep the brand only as compatibility ("works with"),
never in the title, icon or developer name.

## 1 · App details
- Title: `Ruta – Scooter Companion`
- Icon: `store/phonepe/icon-512.png` (512×512 PNG, square, no transparency)
- Type: Apps · Category: Auto and Vehicles · Age group: 16 & Above
- Category declaration: None of the above

## 2 · Short description (≤ 80 chars)
Live battery, rides and Maps directions on your Vida scooter's display.

## 3 · Full description
Unofficial, open-source companion for Hero Vida electric scooters. Not affiliated with or endorsed by
Hero MotoCorp Ltd. or Vida; those names are used only to describe compatibility.

Ruta connects to your scooter over Bluetooth and shows what matters, in big numbers:

• Live cluster: speed, front and rear pack charge, range, drive / standby / charging
• Battery you can read: health, temperature, efficiency (km per 1 %), "Full in" while charging
• Directions on the scooter: turn arrow, distance, road name and arrival from Google Maps; "Then left" for back-to-back turns
• Range check: "12 km spare", "Tight" or "Short by 4 km" for the route you are on
• Music: song, artist and volume on the display; handlebar buttons play, skip and change volume
• Calls: caller name on the display; answer or reject from the handlebar; missed calls and messages
• Show who messaged (optional): the sender's name on the scooter display when no music is playing
• Ride receipts: route map, start and end, distance, speeds and battery used for every ride
• Insights: 24 h / 7 d / 30 d charts for charge, range, temperature, health and charging
• Find my scooter: remembers where you parked; one tap for walking directions
• Alerts: charged to 80 / 90 / 100 %, charging stopped, not enough range for the route
• Several scooters, each with its own Bluetooth PIN, stored encrypted on the phone
• Demo mode: try every screen without a scooter

Privacy: your data stays on your phone. No account, no ads, no analytics, no Ruta server; place names come from Android's Geocoder.

Works with Hero Vida scooters that have the Bluetooth telematics unit (tested on VX2). Android 11+.
To see directions on the scooter: long-press the toggle button › Menu › Display › Widgets › Navigation › On.

Source code (Apache-2.0): https://github.com/CODESURGEN/ruta

## 4 · Support & website
- Website: https://github.com/CODESURGEN/ruta
- Support email: repalamanideep54@gmail.com
- Privacy policy: https://codesurgen.github.io/ruta/privacy (after GitHub Pages is enabled; source docs/privacy.md)

## 5 · Permission declarations (paste per permission if asked)
- Nearby devices (BLUETOOTH_SCAN/CONNECT; BLUETOOTH/BLUETOOTH_ADMIN on Android 11 only): find and connect to the
  scooter. Scan is declared neverForLocation (Android 12+).
- Location (fine/coarse) + foreground service (location, connectedDevice): record rides, remember where you
  parked and count down distance to the next turn, while a ride or scooter link is active or while Ruta is open
  during Google Maps navigation. No background-location permission. On Android 11, Android requires Location to
  scan for Bluetooth devices.
- Notification access (NotificationListenerService): reads Google Maps navigation notifications (turn-by-turn),
  the media session (now playing), the caller name from call notifications, and missed-call and message
  notifications from any app to show directions, music, the caller and missed-call / unread counts on the
  scooter display. Optional "Show who messaged" (off by default) also shows a new message's app and sender
  name, never its text, on the scooter display. Nothing is sent off the phone; a local 7-day diagnostics log may keep parts of it.
- READ_PHONE_STATE: know when a call is ringing, active or ended, for the scooter's call screen.
- READ_CALL_LOG: Android only provides the caller's number to apps holding this permission; the number is shown
  on the scooter display. Ruta does not copy the call log.
- READ_CONTACTS: turn that number into the contact name for the display.
- ANSWER_PHONE_CALLS: accept or reject calls from the handlebar buttons.
- POST_NOTIFICATIONS: ride / charging status and charge alerts.
- INTERNET: Android's Geocoder, to turn ride start, end and parking points into place names.
Nearby devices is required (and Location on Android 11). Location and notifications are asked for during setup
or at the first scooter connection; phone, call and notification access only when the user turns them on.

## 6 · Notes for the reviewer
- No scooter needed: on the welcome screen tap "Explore demo" (or More › Diagnostics › Demo mode). Demo mode
  shows every screen with synthetic data and never uses Bluetooth.
- The app opens and runs with optional permissions denied; each feature explains what it needs.
- Open source: https://github.com/CODESURGEN/ruta. No ads, analytics or accounts; its only network use is Android's Geocoder.
- Signing certificate SHA-256: CE:53:91:68:9D:03:E0:54:31:AE:4E:B1:A7:E7:82:7D:B1:2C:D3:CD:2C:3A:63:A5:11:76:7E:06:63:0E:BE:D3

## 7 · Assets
- icon-512.png · feature-1024x500.png · screenshots/ (demo mode, no brand logos)
