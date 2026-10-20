# PocketGNSS R8 rules.
#
# Keep the app's own classes and names unobfuscated: settings and logged files store enum names and read
# them back with valueOf(). The app's own code is small, so this costs almost nothing.
-keep class com.rfsat.pocketgnss.** { *; }
-keepnames class com.rfsat.pocketgnss.** { *; }

# usb-serial-for-android drivers are instantiated by reflection from the prober's table.
-keep class com.hoho.android.usbserial.driver.** { *; }

# Readable stack traces in Play's crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
