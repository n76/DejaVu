Déjà Vu - A Local RF Based Backend
==================================
This is a backend for [UnifiedNlp](https://github.com/microg/android_packages_apps_UnifiedNlp) that uses locally acquired WLAN/WiFi AP and mobile/cellular tower data to resolve user location. Collectively, “WLAN/WiFi and mobile/cellular” signals will be called “RF emitters” below.

Conceptually, this backend consists of two parts sharing a common database. One part passively monitors the GPS. If the GPS has acquired and has a good position accuracy, then the coverage maps for RF emitters detected by the phone are created and saved.

The other part is the actual location provider which uses the database to estimate location when the GPS is not available.

This backend uses no network data. All data acquired by the phone stays on the phone.

<a href="https://f-droid.org/packages/org.fitchfamily.android.dejavu/" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="90"/></a>

Yet Another Location Backend
============================
This grew out of frustration with my earlier [mobile tower backend’s](https://github.com/n76/Local-GSM-Backend) two major faults:
1. Need to periodically download a huge database. Only feasible when using WLAN/Wifi or fast unlimited mobile/cellular data.
2. Despite repeated use of stumbling apps for the two projects that maintain tower data, the database never had good results for some very rural areas I frequently visit.

I decided that I wanted a mobile/cellular backend that worked the same way as [my WLAN/WiFi backend](https://github.com/n76/wifi_backend). Initially I considered adding mobile/cellular support to the WLAN/WiFi backend.

However, the WLAN/WiFi backend had grown over time and had developed complex settings that had to be repeatedly explained.

Thus this new backend that has been written from scratch, admittedly with some copying and much inspiration from the two previous backends.

It is structured so that adding additional RF emitter types like Bluetooth should be easy. However based on warnings about high resource use when scanning for Bluetooth and the high probability that a Bluetooth device will be mobile that has not been implemented.

Requirements on phone
=====================
This is a plug-in for [µg UnifiedNlp](http://forum.xda-developers.com/android/apps-games/app-g-unifiednlp-floss-wi-fi-cell-tower-t2991544) which can be [installed from f-droid](https://f-droid.org/repository/browse/?fdfilter=unified&fdpage=1&page_id=0). The [µg GmsCore](http://forum.xda-developers.com/android/apps-games/app-microg-gmscore-floss-play-services-t3217616) can also use this backend.

Setup on phone
==============
In the NLP Controller app (interface for µg UnifiedNlp) select the "Déjà Vu Location Service". If using GmsCore, then the little gear at microG Settings->UnifiedNlp Settings->Configure location backends->Déjà Vu Location Service is used.

When enabled, microG will request you grant location permissions to this backend. This is required so that the backend can monitor mobile/cell tower data and so that it can monitor the positions reported by the GPS.

Note: The microG configuration check requires a location from a location backend to indicate that it is setup properly. However this backend will not return a location until it has mapped at least one mobile cell tower or two WLAN/WiFi access points. So it is necessary to run an app that uses the GPS for a while before this backend will report information to microG. You may wish to also install a different backend to verify microG setup quickly.

Collecting RF Emitter Data
======================
To conserve power the collection process does not actually turn on the GPS. If some other app turns on the GPS, for example a map or navigation app, then the backend will monitor the location and collect RF emitter data.

What is stored in the database
------------------------------
For each RF emitter detected an estimate of its coverage area (center and radius) and an estimate of how much it can be trusted is saved.

For WLAN/WiFi APs the SSID is also saved for debug purposes. Analysis of the SSIDs detected by the phone can help identify name patterns used on mobile APs. The backend removes records from the database if the RF emitter seems to have moved, has disappeared or has a SSID that is associated with WLAN/WiFi APs that are often mobile (e.g. "Joes iPhone").

Clearing the database
---------------------
This software does not have a clear or reset database function built it but you can use settings->Storage>Internal shared storage->Apps->Déjà Vu Location Service->Clear Data to remove the current database.

Moved RF Emitter Handling
=========================
For position computations we wish to only use stationary RF emitters. For mobile/cellular towers this is not a huge problem. But with transit systems providing WiFi, car manufacturer's building WiFi hotspots into vehicles and the general use of WiFi tethering on mobile/cell phones, moving APs is an issue.

This backend attempts to handle that in several ways.
1. If the SSID of a WiFi AP matches a known pattern for an AP that is likely to be moving, the AP is “blacklisted”. Examples include SSIDs that have the name of a known transit company, SSIDs that contain "iphone" in the name, etc. You can examine the black list logic in the blacklistWifi() method of the [RfEmitter class](https://github.com/n76/DejaVu/blob/master/app/src/main/java/org/fitchfamily/android/dejavu/RfEmitter.java).
2. A RF Emitter needs to be seen multiple times in locations that are reasonably close to one another before it is trusted.
3. If the implied coverage area for a RF emitter is implausibly large, it is assumed that it has moved. Moved emitters will not be trusted again until they have a number of observations compatible with their new location.
4. When a scan completes, the RF emitters are grouped by how close they are to one another. An emitter that is implausibly far from others ends up in its own group. We use the largest group of emitters to compute location.
5. If we have a good location from the GPS we see if there are any RF emitters that we should have seen on our RF scan but did not. If we expected to see an emitter but didn’t, then we lower our level of trust in the emitter’s location.
6. If our trust of an emitter’s location goes too low (i.e. we haven’t seen it in a long while in an area where we expect to see it), we remove the emitter from our database.

Permissions Required
====================
|Permission|Use|
|:----------|:---|
ACCESS_COARSE_LOCATION|Allows backend to determine which cell towers your phone detects.
ACCESS_FINE_LOCATION|Allows backend to monitor position reports from the GPS.

Note: The backend uses standard Android API calls to determine if it has sufficient privileges before attempting privileged operations. But there appears to be an issue with some versions of CyanogenMod where the permission check succeeds when the backend does not actually have the permissions is needs. The result is a continuous series of force closes which can lock up the Launcher UI or the phone. **If you are using CyanogenMod, you should grant permissions to Dévá Vu prior to selecting it in the settings UnifiedNlp or microG.** LineageOS 14.1 does not have this issue nor does it seem to appear on AOSP based ROMs. See issues [2](https://github.com/n76/DejaVu/issues/2) and [8](https://github.com/n76/DejaVu/issues/8) in the bug list for details.

Changes
=======
[Revision history is kept in a separate change log.](CHANGELOG.md)

Credits
=======
The Kalman filter used in this backend is based on [work by @villoren](https://github.com/villoren/KalmanLocationManager.git).

License
=======

Most of this project is licensed by GNU GPL. The Kalman filter code retains its original MIT license.

Icon
----
The icon for this project is derived from two sources:

[A globe icon](https://commons.wikimedia.org/wiki/File:Blue_globe_icon.svg) and [a map pin icon](https://commons.wikimedia.org/wiki/File:Map_pin_icon.svg) both released under a [Creative Commons share alike license](https://creativecommons.org/licenses/by-sa/3.0/deed.en).

GNU General Public License
--------------------------
Copyright (C) 2017-18 Tod Fitch

This program is Free Software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License

MIT License
-----------
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
