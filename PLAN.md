# Bike Link: Plan

Working name. The folder is called `CarPlay`, but CarPlay was ruled out because it only gives car routes and needs Apple-licensed hardware.

## Goal

You get on the motorbike and choose where to go from your iPhone, or by voice through the helmet intercom. The Android phone on the holder opens Google Maps with two-wheeler routes. You never touch the Android phone.

## What v1 does

- The Android phone connects to the iPhone's internet automatically.
- You send a destination from the iPhone in any of these ways:
  - Share → **Send to Bike** from Google Maps, Apple Maps or a WhatsApp location
  - **Favourite** buttons such as Home, Office or Petrol pump, on the iPhone home screen or Control Center
  - **Siri** through the intercom: "Ride home", "Ride to …", "Find petrol pump"
- The Android phone opens Google Maps in two-wheeler mode and starts navigating.
- Sending a new destination while riding switches the route automatically. The **Maps helper** confirms Maps' "Exit navigation?" dialog for you.
- **Stop Ride** from the iPhone ends navigation, also through the Maps helper.
- The iPhone confirms with "Navigating to X" or "Ride stopped", which Siri speaks aloud in the helmet.

**Not in v1:** iPhone calls or notifications on the bike screen, and our own map.

## Works on any Android phone

- One app for every brand on **Android 8.0 or newer**. Older versions can't run Google Maps anyway.
- Differences between Android versions are handled inside the app, for example:
  - background launch rules (Android 10)
  - Bluetooth permissions (Android 12)
  - the notification permission (Android 13)
  - background service rules (Android 14 and 15)
- Some brands close background apps aggressively: Xiaomi/Redmi/POCO, Realme/Oppo/OnePlus, Vivo/iQOO, and Samsung. The first-run setup screen walks through the right settings for whichever brand it's running on.
- Testing: Android emulators for each version from 8 to 17, plus your real phone.
- **Heads-up:** a report from October 2025 says the Google Maps app now needs Android 12L or newer. I haven't confirmed that against Google's own notes. If it's true, phones older than 12L keep the Maps version they already have, but it no longer updates. The weak spot would then be Google Maps, not our app.

## How it works

```
iPhone                                         Android phone on the bike
──────                                         ─────────────────────────
Shortcuts:                                     Bike Link (always running)
 • Send to Bike (Share menu)                     1. Listens for the iPhone; accepts only
 • Favourites (buttons)    ── command + ───▶        commands with your secret key
 • Siri via intercom          secret key         2. Works out the destination: a place name,
                              (over the iPhone      coordinates, a Google Maps link or an
Shares internet ───────────▶  hotspot)              Apple Maps link
                                                 3. Opens Google Maps in two-wheeler navigation
```

### Android app (Kotlin)

1. **Background service:** runs all the time, starts when the phone boots, and accepts only commands that carry your secret key.
2. **Destination handling:** turns any input into a destination. It expands Google Maps short links and reads the coordinates from Apple Maps links.
3. **Maps launcher:** opens Google Maps in two-wheeler navigation and wakes the screen if it's off.
4. **Setup screen:** a one-time checklist covering:
   - permissions
   - battery and auto-start settings for your brand
   - "Display over other apps"
   - turning on the Maps helper. On Android 13 and newer, you first have to tap "Allow restricted settings" in the app's info screen, because Android blocks accessibility access for apps not installed from the Play Store until you allow it.
   - a pairing code to enter on the iPhone
5. **Status screen:** shows "Connected to iPhone ✓ · Waiting for destination" and keeps the screen awake while the phone is charging.
6. **Maps helper (optional, uses the accessibility permission):** taps buttons in Google Maps for you, and only right after a command from your iPhone:
   - **Yes** on "Exit navigation?" when a new destination arrives mid-ride
   - **Close navigation** when you send Stop Ride

   It finds buttons by their labels, the same ones that showed up in the Phase 0 tests. It does nothing else, reads nothing else, and it's off until you turn it on. If it's off, or a button can't be found, the rider taps once instead.

### iPhone side: Shortcuts only

There's no iPhone app and no Apple developer account. The Shortcuts are:

- Send to Bike
- Ride Home
- Ride to Office
- Ride to… (Siri asks where)
- Find Petrol Pump
- Stop Ride

The iPhone is a 17 Pro Max on iOS 27.2.1, so the Shortcuts can also be reached through:

- the **Action button**: one press opens a "Bike" menu with Ride Home, Ride to…, Petrol Pump and Stop Ride
- **Control Center** and **Lock Screen** buttons, which run without unlocking the phone

You enter the pairing code once, when you install them.

### Supporting setup

- **Internet:** the iPhone hotspot over Wi-Fi with Maximize Compatibility on, or over Bluetooth. We pick one in Phase 0.
- **Android lock screen:** it would cover Google Maps. Either turn it off on the bike phone, or use Smart Lock with the intercom as a trusted device, where the phone supports it.
- **Intercom:** pair it with both phones. The iPhone handles calls, music and Siri; the Android phone handles Maps voice. We check how your model mixes navigation voice over music. Start Siri with the intercom button, because wind noise makes "Hey Siri" unreliable.
- **Cost:** none. It uses the free Google Maps app, so there's no API key, billing, server or Apple developer fee.

## Phases

### Phase 0: test the risky parts (one session with your phones)

1. **Automatic internet:** does the Android phone rejoin the iPhone without you touching either phone? Compare Wi-Fi and Bluetooth.
2. **iPhone to Android:** can a Shortcut reach the Android phone over the hotspot? We test with a small throwaway receiver.
3. **Two-wheeler link:** does it open Google Maps directly in two-wheeler navigation?
4. **Siri from a pocket:** with the iPhone locked, does the intercom button plus "Ride home" run the Shortcut?
5. **Intercom audio:** can Maps voice from the Android phone and music from the iPhone play together?

Outcome: a yes or no for each piece, and a decision on the internet method and how commands are sent.

#### Phase 0 results

Test phone: OnePlus 7 (GM1901), Android 12 (API 31), Google Maps 26.40. This Maps version needs Android 10 or newer, so the earlier "Maps needs 12L" report was wrong.

- **Test 3, two-wheeler link: ✅ PASS (2026-11-13).**
  - `google.navigation:q=<place>&mode=l` opens straight into two-wheeler turn-by-turn navigation with no taps. Same trip in car mode (`mode=d`): 1,187 km / 18 h 18 m. In two-wheeler mode: 1,034 km / 19 h 19 m, a different route.
  - The web-style link (`https://www.google.com/maps/dir/?api=1&…&travelmode=two-wheeler&dir_action=navigate`) does **not** start navigation. It only opens the place page, so we don't use it.
  - ⚠️ **Changing destination mid-ride:** if a new destination arrives while Maps is already navigating, Maps shows an "Exit navigation?" dialog with a countdown. If nobody taps, it **keeps the old route**. Options:
    - (a) the rider taps "Yes" once
    - (b) an optional helper using Android's accessibility permission taps "Yes" for us. The same helper could also handle "stop navigation" from the iPhone, which v1 had dropped. It's off by default, the user turns it on, and it depends on Maps' button labels.
    - **Decision: ship both.** (b) is the Maps helper and is now in v1, along with Stop Ride. (a) is the automatic fallback when the helper is off or can't find the button. The buttons it needs ("Exit navigation?" → "Yes"/"No", and "Close navigation") showed up as labels in Android's accessibility tree during testing, which is the same view the helper will use.
- **Test 2a, iPhone → Android over the hotspot (Safari): ✅ PASS (2026-11-13).**
  - The OnePlus joined the iPhone hotspot and got the address 172.20.10.3. The iPhone (the gateway) is 172.20.10.1. Ping from the phone to the iPhone took about 21 ms, and the phone could reach the internet.
  - A small test page served from the phone (port 8765) loaded in iPhone Safari, and the request showed up in the phone's log. **Direct local commands work, so no cloud relay is needed.**
  - Note: the phone's address is assigned by the hotspot and could change. The app has to handle that, either by checking the hotspot's 13 possible addresses or by the phone reporting its address.
- **Test 2b, the same request from a Shortcut:** pending.
- Tests 1, 4 and 5 still need the iPhone and the intercom.

### Phase 1: Android app core

The background service, receiver, destination handling, Maps launcher, status screen and Maps helper. The helper handles the mid-ride "Exit navigation?" confirmation and Stop Ride.

#### Phase 1 results on the OnePlus 7 (2026-11-13)

- ✅ **Listener and bike code:** a wrong code gets a 401 and the right code a 200. The code works with or without the dash, in any letter case.
- ✅ **Navigating from the background** (home screen, Bike Link not open) works once "Display over other apps" is allowed. Without it, Android blocks the launch (`Abort background activity starts`), so the iPhone now gets a "finish setup" reply instead of a false "Navigating".
- ✅ **Switching mid-ride:** the helper answers "Yes", and Maps reroutes (701 km → 837 km).
  - The first attempt failed: putting even an invisible screen over a navigating Maps pushed Maps into **picture-in-picture**. Fixed by opening Maps directly when the screen is on.
- ✅ **Stop Ride:** the helper taps "Close navigation". It now taps again until navigation has actually ended.
- ⚠️ **OnePlus blocks Bike Link from starting itself** at boot and after updates (`OplusAppStartupManager`). Worked around: Android itself always starts the Maps helper, and the helper starts the listener.
- ❌ **OnePlus freezes Bike Link when the screen is off** (process in `freezer:/frozen`), even with a foreground service and the battery exemption. Commands sent while the screen is off wait until the screen comes back on. Next step: test OnePlus's own per-app "Allow background activity" setting. If that doesn't help, keep the bike phone's screen on while it's charging.
- Note: at home the phone switched to the home Wi-Fi, which takes it off the iPhone hotspot. On the bike that Wi-Fi is out of range, but it explains "not on the iPhone hotspot" at home.

### Phase 2: iPhone Shortcuts and pairing

All five Shortcuts, the pairing code, and the spoken and on-screen confirmations.

### Phase 3: make it work on any Android

The brand-specific setup steps, tests on emulators for Android 8 to 17, and checks that it survives reboots, sleep and hours of riding.

### Phase 4: real rides and polish

Real rides, clear error messages, an app icon, and a setup guide.

### Later (optional)

iPhone calls and WhatsApp on the bike screen, a ride log, and a Play Store release. A Play Store release would need Google's approval for the accessibility helper, or a Play Store build without it.

## Risks and fallbacks

| Risk | Fallback |
|---|---|
| The iPhone hotspot doesn't appear automatically | Use Bluetooth, or turn on Maximize Compatibility; worst case, one tap on the iPhone when you start |
| A Shortcut can't reach the Android phone directly | Send commands through a free internet relay |
| The link opens car mode instead of two-wheeler | Try the other Android link formats |
| The phone's brand closes the app in the background | Brand-specific setup steps; the phone stays on charge |
| A Google Maps update breaks the link | The link code is kept in one small, separate part of the app, so it's easy to fix |
| A Maps update or a non-English phone language renames the buttons the helper looks for | The helper fails safely: nothing gets tapped, and the rider taps "Yes" or ✕ once. The labels live in one place, so they're easy to update |

## Decisions so far

- No CarPlay and no adapter.
- Use the free Google Maps app, not Google's paid navigation kit.
- The iPhone side is built with Shortcuts, not an iPhone app.
- Supports Android 8.0 and newer, on every brand.
- Built in Kotlin, not Flutter, because the app is mostly background Android work with very little UI. Confirmed.
- No Android Studio. We use the command-line Android SDK and JDK 21 with VS Code, and build and install from the terminal. JDK 21 was chosen over 17, which is only the minimum, and over the newest JDK, which is ahead of what the Android build tools are tested with. The JDK only runs the build; it never runs on the phone.

## Needed before Phase 0

1. ✅ Installed on 2026-11-13: JDK 21 (Amazon Corretto, at `~/Library/Java/JavaVirtualMachines`) and the Android SDK (at `~/Library/Android/sdk`) with command-line tools 23, platform-tools 37.0.1 (adb), build-tools 37.0.0 and the API 37 platform. The SDK license was accepted.
2. Developer options and USB debugging turned on for the Android phone, with the phone connected to the Mac.
3. The intercom model.
4. The iPhone model and iOS version.
5. Favourite places (these can wait until Phase 2).
