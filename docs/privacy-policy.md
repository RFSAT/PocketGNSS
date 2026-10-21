# PocketGNSS privacy policy

RFSAT Limited, October 2026. Applies to the Android app PocketGNSS (com.PocketGNSS).

**What the app handles.** PocketGNSS reads the position computed by a GNSS receiver you connect (USB, Bluetooth
or network) or, if you choose the phone as the source, the phone's own GNSS position and raw satellite measurements,
which it processes on the phone (phone RTK). It shows the position, and only if you ask it to, it records it to files on your phone, sends it to an NTRIP
correction service you configure, gives it to other apps on your phone as a mock location, or serves it to
programs connecting to the app's NMEA server or JSON stream, or to apps you allow to receive its position broadcast.
Photos you take for points stay in the app's folder unless you export them.

**What leaves the phone, and to whom.**
* To the NTRIP caster you enter (for example your RTK correction provider): your user name and password for that
  service, and, when the caster requires it, your approximate position in an NMEA GGA message, as the NTRIP
  protocol needs for network RTK. Nothing is sent if you do not switch NTRIP on.
* To the map tile servers (OpenStreetMap, Esri, OpenTopoMap): the tile requests for the area shown on the Map tab,
  as with any web map. No position or identifier is added by the app.
* To devices on your Wi-Fi or hotspot, only if you switch them on: the NMEA / JSON position streams and the
  corrections relayed by the app's NTRIP caster (with the login you set).
* To the ephemeris caster you choose for phone RTK (with that account), only if you switch the ephemeris stream on.
* Nothing is sent to RFSAT Limited. The app contains no analytics, advertising or tracking.

**What stays on the phone.** Settings (the NTRIP password encrypted with a key in the phone's Android Keystore), recorded sessions (including
raw GNSS recordings) and saved points are
stored in the app's private storage and its own folder on the phone. You can share or delete them from the Log
tab; uninstalling the app removes them.

**Permissions.** Bluetooth (connect to the paired receiver), location (to keep providing the receiver's position
as a mock location and keep recording while the screen is off), mock location (only when you select the app in
Developer options), notifications (status of background recording), network (NTRIP, map tiles, the receiver's
Wi-Fi settings page, the local NTRIP caster and position streams). The app defines one permission of its own,
"PocketGNSS position", which other apps must be granted by you before they can receive its position broadcast.
Point photos are taken with your camera app; PocketGNSS itself does not request the camera permission.

**Contact.** RFSAT Limited, www.rfsat.com.
