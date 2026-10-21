# Using the PocketGNSS position in your own programs

PocketGNSS offers its position (RFSAT receiver, any external receiver or phone RTK) to other programs in four ways.
All of them are off until switched on in **Setup > Outputs**.

| Way | For | Setup switch |
|---|---|---|
| NMEA over TCP (port 10110) | GIS and navigation software that reads NMEA | NMEA TCP server |
| **JSON over TCP (port 10111)** | your own scripts and apps (MATLAB, Python, Android) | Interface for your own apps > JSON position stream |
| **Broadcast intent** | your own Android apps on the same phone | Interface for your own apps > Position broadcast to apps |
| Bluetooth serial (SPP) | laptops, tablets, field controllers | Phone as GNSS receiver |

With *This phone only* on, the TCP streams are only reachable from apps on the phone (127.0.0.1). Switch it off to
reach them from a laptop on the same Wi-Fi or on the phone's hotspot (address shown in Setup); anyone on that network
can then read the position.

## JSON position stream

One line per position (`\n`-terminated), at the receiver's rate:

```json
{"utc":"12:00:00.00","date":"2026-10-21","lat":40.756700060,"lon":22.121299900,"h_ell":74.002,"h_msl":38.002,
 "fix":"RTK_FIXED","gga_quality":4,"method":"RTK fixed","h_acc":0.009,"v_acc":0.011,"sd_lat":0.006,"sd_lon":0.007,
 "sd_h":0.011,"sats":12,"hdop":0.71,"speed":0.012,"course":null,"age":0.6,"station":"0089",
 "grid":{"name":"ETRS89 / UTM 34N","e":594649.860,"n":4512353.720},"height":{"system":"EGM2008","h":37.981}}
```

| Field | Meaning |
|---|---|
| `utc`, `date` | time of the fix (UTC) and date when known |
| `lat`, `lon` | degrees, ETRS89 with corrections from a European network and with PPP in Europe, else WGS 84 / ITRF |
| `h_ell`, `h_msl` | ellipsoidal height and height above sea level, m |
| `fix`, `gga_quality` | NONE, SINGLE, DGNSS, RTK_FLOAT, RTK_FIXED... and the NMEA GGA quality number |
| `method` | phone RTK only: which method produced the position |
| `h_acc`, `v_acc` | 1-sigma horizontal and vertical accuracy, m (from GST when the receiver sends it) |
| `sd_lat`, `sd_lon`, `sd_h` | GST standard deviations, m (null without GST) |
| `sats`, `hdop` | satellites used, horizontal dilution of precision |
| `speed`, `course` | m/s and degrees from north (null when standing) |
| `age`, `station` | age of the corrections (s) and base station ID |
| `grid` | only when a grid is chosen in Setup > Display |
| `height` | only with a national height model (Setup > Display > Height > National): its height system and the height in it, m |

Unknown values are `null`.

### MATLAB

```matlab
t = tcpclient("127.0.0.1", 10111);            % from a laptop: the phone's address shown in Setup
configureTerminator(t, "LF");
for k = 1:100
    p = jsondecode(readline(t));
    fprintf("%s  %.9f %.9f  %s  ±%.3f m\n", p.utc, p.lat, p.lon, p.fix, p.h_acc);
end
clear t
```

### Python

```python
import json, socket
with socket.create_connection(("127.0.0.1", 10111)) as s:
    for line in s.makefile("r", encoding="ascii"):
        p = json.loads(line)
        print(p["utc"], p["lat"], p["lon"], p["fix"], p["h_acc"])
```

## Broadcast intent (Android)

PocketGNSS sends `com.PocketGNSS.action.POSITION` up to five times a second, only to apps that hold the
permission `com.PocketGNSS.permission.POSITION`. It is a "dangerous" permission: the user grants it to your app
like the location permission (install PocketGNSS first, so the permission is defined).

```xml
<!-- AndroidManifest.xml of your app -->
<uses-permission android:name="com.PocketGNSS.permission.POSITION" />
```

```kotlin
// Ask once: requestPermissions(arrayOf("com.PocketGNSS.permission.POSITION"), 1)
val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val lat = i.getDoubleExtra("lat", Double.NaN)
        val lon = i.getDoubleExtra("lon", Double.NaN)
        val fix = i.getStringExtra("fix")             // "RTK_FIXED", "RTK_FLOAT", ...
        val acc = i.getDoubleExtra("h_acc", Double.NaN) // m, 1 sigma
        val hEll = i.getDoubleExtra("h_ell", Double.NaN)
    }
}
// Register while your app runs (implicit broadcasts do not reach manifest receivers):
ContextCompat.registerReceiver(context, receiver, IntentFilter("com.PocketGNSS.action.POSITION"), ContextCompat.RECEIVER_EXPORTED)
```

Extras: `lat`, `lon`, `h_ell`, `h_msl`, `fix`, `gga_quality`, `h_acc`, `v_acc`, `speed`, `course`, `age`, `sats`,
`method` (phone RTK), `time_ms` (phone clock, ms), and with a national height model `h_national` (m) and
`height_system`. Absent extras mean unknown.

## Why not mock location?

PocketGNSS can also replace the phone's location with the receiver's (Setup > Outputs > Mock location), which every
app then sees. That needs Developer options and hides that the position is external; the JSON stream and the
broadcast keep the fix type and accuracy and leave the phone's own location untouched.
