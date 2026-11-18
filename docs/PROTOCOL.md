# Scooter BLE protocol notes

Ruta's own notes on the Bluetooth Low Energy link between an Android phone and the telematics
control unit (TCU) of Hero Vida scooters, written from interoperability testing with a VX2 (TCU
firmware 0.2.88). These are interoperability notes in our own words: byte layouts are what Ruta
sends and what it has seen the scooter send, and nothing here is copied from another app. Field meanings marked *inferred* come from matching values
against the scooter's own display.

**Scope.** Only plaintext channels are used. The command service (service two, characteristics
0x0201–0x0203) is encrypted with a cloud-issued key; Ruta never reads, writes or subscribes to
it, and does not try to bypass anything the scooter or its maker enforces.

All multi-byte numbers are big-endian. Characteristic UUIDs are 16-bit values on the Bluetooth
base UUID (`0000xxxx-0000-1000-8000-00805f9b34fb`); Ruta looks characteristics up by UUID
across all services, because the same short UUID is not reused.

## Services and characteristics

| Service | Characteristic | Properties | Use |
|---|---|---|---|
| One: TCU info `1e5af1a3-fab3-4e33-aafb-331e781ed789` | `0x0101` | read | TCU info: IMEI, VIN, firmware, BLE protocol, hardware |
| | `0x0102` | write | Phone OS: `01` = Android |
| | `0x0103` | write | Phone heartbeat (reply to the TCU's) |
| | `0x0104` | notify | TCU heartbeat |
| | `0x0105` | read, notify | Vehicle state |
| | `0x0106` | notify | Firmware-update (ESYNC) state; logged only. Optional: absent on the VX2 |
| Two: commands `02ce005e-2c40-455d-a079-976d3a35c630` | `0x0201`–`0x0203` | — | Encrypted. **Never used.** |
| Three: cluster ("ICBT") `76f2f660-db96-4e05-a871-53d0e4524e50` | `0x0301` | write (with response) | Phone status, 1 Hz |
| | `0x0302` | write (with response) | Caller name or number |
| | `0x0303` | write (with response) | Turn-by-turn frame |
| | `0x0304` | write (with response) | Music state |
| | `0x0305` | write (with response) | Song title and artist |
| | `0x0306` | read, indicate | Handlebar buttons |
| | `0x0307` | write (with response) | Voice-assistant state |
| Four: stats `f453986a-…` (found by characteristic UUID) | `0x0401` | write | Stats subscription |
| | `0x0402` | notify | Stats frames (tag in byte 0) |

## Connection sequence

1. Connect GATT (LE transport). If Android is not bonded with the scooter, call `createBond()` first;
   the scooter asks for its Bluetooth PIN (shown in the maker's app). Ruta answers the pairing
   request with the PIN the rider saved, so pairing is silent on most phones.
2. Wait about 1 s, discover services, request MTU 247 (26-byte stats frames need at least 29).
3. **Stage A:** enable `0x0104` notifications, read `0x0101`, send the first phone heartbeat.
4. About 1 s later, **stage B:** enable `0x0105` notifications (and `0x0106` when present), write `0x0102 = 01`,
   read `0x0105`, enable `0x0402` notifications, **enable `0x0306` indications**, request the BMS
   stream (stats tag 1).
5. Ready. From now on, once a second: `0x0301` phone status, `0x0302` caller during calls,
   `0x0303` turn-by-turn while Google Maps navigates, `0x0304`/`0x0305` music.

The `0x0306` subscription matters beyond the buttons: in testing, the TCU only showed turn-by-turn
frames after the phone had subscribed to it. Ruta does not read or change any feature entitlement.

One GATT operation is outstanding at a time. A write gets up to three attempts in total; periodic
frames get one (`0x0301`, `0x0302`) or two (`0x0303`–`0x0305`) and replace an older queued frame of
the same characteristic; turn-by-turn frames use a
priority lane directly behind the running operation (see `PriorityLane` in `TcuProtocol.kt`).

## Heartbeat (0x0103 / 0x0104)

The TCU notifies a counter on `0x0104` (2 or more bytes, big-endian; fewer than 2 bytes count as
0). The phone replies on `0x0103` with the value + 1 as a 2-byte big-endian number. No TCU
heartbeat for 30 s means the link is gone.

## TCU info (0x0101), at least 53 bytes

| Bytes | Field |
|---|---|
| 0–14 | IMEI (ASCII) |
| 15–31 | VIN (ASCII) |
| 32–34 | Firmware major.minor.patch |
| 36–38 | BLE protocol version |
| 39 | Hardware version (ASCII) |
| 40–52 | Part number (ASCII) |

Ruta shows firmware, hardware and protocol in Diagnostics and never logs the IMEI or VIN.

## Vehicle state (0x0105), 6 bytes

| Byte | Bits | Field |
|---|---|---|
| 0 | 7 | Ignition on |
| 0 | 6 | Immobilised |
| 0 | 2–5 | Handle lock (1 = locked) |
| 2 | 4–7 | Ride mode (number; names not mapped yet) |
| 3 | 4–7 / 0–3 | Regeneration level / acceleration level |
| 4 | — | Speed limit (km/h) |

## Stats (service four)

Subscribe with `0x0401 = [tag, enable, 0x00, period]`, `period` in milliseconds and a multiple of
100. Only the low byte is carried, so periods above 255 do not work. On the VX2, period 200 streams
about twice a second and period 0 returns one frame. Frames arrive on `0x0402` with the tag in
byte 0.

Tags seen: 1 BMS, 2 dynamics, 3 static, 4 EV faults, 5 motor controller, 6 temperature, 7 GNSS,
8 IMU, 9 alerts, 10 network, 11 ECU, 12 ICE faults, 13 status. Only tag 1 is decoded. Diagnostics
(and an automatic probe 10 s after connecting) request the others once and log their raw bytes,
so fields such as the scooter's own range estimate can be identified later.

### BMS frame (tag 1), 26 bytes

| Bytes | Field | Unit (*inferred*) |
|---|---|---|
| 0 | Tag = 1 | |
| 1–2 | Scooter state of health | % × 100 |
| 3–4 | Scooter state of charge (average of both packs) | % × 100 |
| 5–6 | Scooter temperature | (°C + 40) × 100 |
| 7, 8 (high nibble) | Charge time (12 bits) | not decoded |
| 8 (low nibble) | BMS status | 4 = drive, 0/2 = standby, others transient |
| 9 | Pack 1 status (high nibble), pack 2 status (low nibble) | as above |
| 10–17 | Pack 1: health, charge, energy, temperature | as above |
| 18–25 | Pack 2: health, charge, energy, temperature | as above |

The scale is detected from the data and latched per scooter (`BmsDecoder`): percentages are
raw ÷ 100 and temperatures raw ÷ 100 − 40 °C on the VX2. Pack 1 sometimes sends raw `0x0028`
(−39.6 °C) as a placeholder; temperatures below −30 °C are dropped. Range is estimated in the app
(charge × learned km per %), because no range field has been identified yet.

## Cluster channel (service three)

Text fields are US-ASCII, cut to the field size and zero-padded (no terminator). Letters, digits,
whitespace and `+` are kept; accented Latin letters fold to their base letter; other characters
become `_` (caller, road) or are removed (music).

### Phone status (0x0301), 8 bytes, every second

| Byte | Bits | Field |
|---|---|---|
| 0 | 0 | Headset connected |
| 0 | 1 | Missed-call alert |
| 0 | 2 | Phone connected (always 1) |
| 0 | 3–5 | Phone battery bars 0–4 |
| 1 | 0–2 | Signal level 0–4 |
| 1 | 3–5 | Call state: 0 none, 1 incoming, 2 outgoing, 3 active, 4 ended, 5 conference |
| 1 | 6 | Unread messages |
| 2 | 0 / 1–6 | Clock sync flag (1) / seconds |
| 3, 4 | — | Minutes, hours (24 h) |
| 5 | 0–3 / 4–7 | Unread messages / missed calls (0–15 each) |
| 6–7 | — | Call duration in seconds (only while active) |

This frame is what makes the cluster show the phone as connected and keeps its clock in sync.

### Caller (0x0302), 14 bytes

The contact name, or the number with only digits and `+`, sent right after `0x0301` while a call
rings or is active. An optional two-part form splits the field into two 7-byte halves.

### Turn-by-turn (0x0303), 27 bytes

| Byte | Bits | Field |
|---|---|---|
| 0 | 0 | Distance unit: 1 = metres, 0 = tenths of a km |
| 0 | 2 | Navigation active |
| 0 | 4–7 | Arrival hour, 12-hour clock |
| 1 | — | Arrival minute |
| 2–3 | — | Distance to the next turn (in the unit of byte 0 bit 0) |
| 4 | — | Turn symbol |
| 5 | 4 | Arrival PM |
| 6 | — | Time left, hours |
| 7 | — | Next turn symbol |
| 8–9 | — | Distance left, tenths of a km |
| 10 | — | Time left, minutes |
| 11–12 | — | Roundabout exit, speed limit (0) |
| 13–26 | — | Road name (14 bytes; the VX2 display shows about 9 characters) |

Turn symbols: `0x42` wrong way, `0x43` slight left, `0x44` slight right, `0x45` sharp left,
`0x46` sharp right, `0x47` straight, `0x48` destination, `0x49` left, `0x4A` right, `0x4B` ramp
left, `0x4C` ramp right, `0x4E` roundabout right, `0x4F` U-turn left, `0x50` U-turn right,
`0x51` fork left, `0x52` fork right, `0x54` car train, `0x55` roundabout left, `0x56` merge,
`0x58` keep right, `0x59` ferry, `0x5A` keep left. Navigation ends with one frame that has the
active bit cleared, 12 AM, distance 0, straight and an empty road.

Ruta builds these frames from Google Maps' navigation notification (read by its notification
listener), counts the distance down between Maps updates with the phone's location, and shortens
road names to fit the display (`RoadNames.kt`). When Maps announces a second maneuver right after
the current one ("Turn left onto X, then turn right"), the road field carries a short hint instead
of the road ("Then R", "Then left", "Then kp L", "Then rdbt", "Then exit", "Then U", the longest
wording that fits the same width budget); byte 7 (next turn symbol) stays straight.

### Music state (0x0304), 3 bytes

Byte 0: volume 0–10 in bits 3–6, bit 7 = 0 while playing. Byte 1: track progress in percent.
Byte 2 bit 0: a media session is active. Not sent during calls.

### Song (0x0305), 28 bytes

Title in bytes 0–13, artist (or album) in bytes 14–27. Twenty-eight zero bytes clear the song.

**Sender alert (Ruta's own reuse, opt-in).** With "Show who messaged" on, while no media is playing and
no call is up, Ruta writes a new message's app as the title and its sender (contact or group name, or
"New message"; never the text or a phone number) as the artist, in the same 28-byte layout, then
restores the previous song frame (empty, or the paused track) after about 8 s, as soon as music starts
or a call rings, or on the next handlebar play/pause, which it consumes. At most one alert per 10 s;
messages in between collapse to the latest sender. `0x0304` is not changed for the alert; whether the
display shows `0x0305` text while no media session is active is still to be confirmed on a scooter.

### Handlebar buttons (0x0306), indications of 3 bytes

| Byte | Bits | Field | Values |
|---|---|---|---|
| 0 | 0–3 | Call action | 1 accept, 2 reject |
| 0 | 4–5 | Call volume | 0 up, 1 down |
| 0 | 6–7 | Music volume | 0 up, 1 down |
| 1 | 3–5 | Music control | 0 play/pause, 1 next, 2 previous, 3 mute |
| 2 | 0–2 | Voice | 1 start, 2 cancel |

Other values mean "no action" (`F0 38 00` has none). One press can decode to several fields;
Ruta acts on the first and ignores an identical frame within 300 ms.

### Voice-assistant state (0x0307), 1 byte

0 idle, 1 listening, 2 processing, 3 responding, 4 cancelled, 5 negative response.
