---
title: Ruta privacy policy
permalink: /privacy
---

# Ruta privacy policy

Effective 17 November 2026 · App: Ruta (`dev.codesurgen.ruta`) · Developer: Repala Manideep (CODESURGEN)

Ruta is an unofficial, open-source companion app for Hero Vida electric scooters. It is not affiliated with
Hero MotoCorp Ltd. or Vida. Source code: https://github.com/CODESURGEN/ruta

## Summary
- Ruta has **no account, no ads, no analytics and no Ruta server**. The developer receives none of your data.
- What Ruta records is kept in Ruta's own app storage on your phone.
- Data leaves the phone only when Android's Geocoder looks up place names for ride and parking points (see
  Internet below), or when **you** choose to export a CSV, share a diagnostics log, share a location or open a
  link in Google Maps.

## What Ruta stores on your phone
- **Scooter link:** your scooter's Bluetooth name and address, and its Bluetooth PIN (encrypted with an Android
  Keystore key).
- **Battery and vehicle readings** received from the scooter (charge, range, temperature, health, odometer),
  battery history and charging sessions.
- **Rides:** GPS points, start and end place, distance, speeds and battery used.
- **Parking spot:** the last place you parked.
- **Diagnostics log:** technical events, kept for 7 days in Ruta's app folder on the phone
  (`Android/data/dev.codesurgen.ruta/files/logs`) and in Android's system log. It can contain the scooter's
  Bluetooth name and address, caller names or numbers, song titles and artists, and Google Maps direction text.
  It is never sent anywhere unless you share it.

## What Ruta reads and why
| Permission | Used for |
|---|---|
| Nearby devices (Bluetooth) | Find and connect to your scooter. On Android 12+ this is not used to find your location. |
| Location | Record rides, remember where you parked, and count down the distance to the next turn. Used while a ride or scooter link is active, or while Ruta is open during Google Maps navigation. No background-location permission. On Android 11, Android also requires Location to scan for Bluetooth devices, so it is needed to find your scooter. |
| Notification access | Read Google Maps navigation notifications (turn-by-turn), what is playing, the caller name from call notifications, and missed-call and message notifications from any app (for example phone, SMS, WhatsApp, Telegram, Signal, Messenger) to show directions, music, the caller and missed-call / unread counts on the scooter display. If you turn on "Show who messaged" (More › Alerts, off by default), the app name and the sender's name (or the group's name) of a new message may be shown on the scooter display for a few seconds; the message text is never read for this, and a sender shown only as a phone number (or a name in a non-Latin script) shows as "New message". Notification contents are not sent anywhere; only the diagnostics log above may keep parts of them for 7 days. |
| Phone, call log, contacts | Know when a call rings, get the caller's number (Android provides it only with the call-log permission) and turn it into a contact name for the scooter display. Ruta does not copy your call log or contact list. |
| Answer calls | Accept or reject a call from the handlebar buttons. |
| Notifications | Ride and charging status, charge and range alerts. |
| Internet | Android's Geocoder turns ride and parking coordinates into place names. On most phones that lookup is handled by Google Play services under Google's privacy policy. Ruta makes no other network requests. |

Nearby devices is required (and Location on Android 11). Everything else is optional: Location and
Notifications are asked for during setup or at the first scooter connection; Phone, call and notification
access only when you turn those features on. You can change any of them in Android settings or under
**More › Permissions**.

## Sharing
Ruta does not sell, share or transfer your data to anyone. Ruta contains no third-party analytics,
advertising or tracking SDKs.

## Your control
- Forget a saved scooter (and its PIN) under **More › Scooters**.
- Delete everything by clearing Ruta's storage in Android settings or by uninstalling it.
- Revoke any permission at any time in Android settings.

## Children
Ruta is meant for scooter riders and is not directed at children.

## Changes
Changes to this policy are published at this address and in the source repository with a new effective date.

## Contact and grievances
Repala Manideep · repalamanideep54@gmail.com · https://github.com/CODESURGEN/ruta/issues
