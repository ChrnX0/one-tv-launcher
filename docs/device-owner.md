# Making the launcher the device owner (without losing your Google account)

Device owner is **optional**. It lets One TV Launcher pin the HOME button, hide preinstalled apps and stop anyone from
force-stopping or clearing it. Only a factory reset removes it, so read this first.

## Why Android normally refuses, and the workaround

`dpm set-device-owner` refuses in two cases:

1. **"already some accounts"**: Android asks the AccountManager which accounts exist
   (`DevicePolicyManagerService.hasIncompatibleAccountsOrNonAdbNoLock`, Android 14). On Google TV the Google account
   is only reported while `com.google.android.gms` is enabled. With GMS **disabled** for a few seconds, the list comes
   back empty. Disabling does not delete data: when GMS is re-enabled, the account is back. Hail and Dhizuku use the
   same trick on phones.
2. **"already several users"**: extra Android users (Google TV creates empty profiles) must be removed first.
   Removing a user **deletes that user's data**.

## From the TV (no computer)

The welcome wizard (`BoasVindasActivity`) shows one step at a time; each line appears when the previous one is done:

1. **Turn on the remote-key service** (the launcher's accessibility service). It draws the helper window over
   Settings and reads the screen.
2. **Turn on Developer options**: the wizard opens *About*; press OK 7 times on the build number.
3. **Pair the launcher with the TV**: Developer options open with the launcher's window on top. Turn on *Wireless
   debugging* and choose *Pair device with pairing code*. The launcher finds the port (mDNS), **reads the code from
   the screen** through accessibility (`CodigoPareamento`) and pairs by itself. If it can't read it within ~6 s, the
   window's keypad stays available.
4. **Make the launcher the device owner** (`VirarDono.kt`): it lists the extra users that will be removed and asks for
   confirmation, disables Google, runs the command and re-enables Google in a `finally` block. If the launcher dies
   halfway, its self-healing re-enables Google.

Later, the same button lives in *Launcher settings → permissions → Device owner*.

## From a computer (adb), the same steps

```
adb shell pm list users                      # note every user that is not 0
adb shell pm remove-user <N>                 # one per extra user (deletes its data)
adb shell pm disable-user --user 0 com.google.android.gms
adb shell dpm set-device-owner --user 0 br.paulo.tvlauncher/.AdminReceiver
adb shell pm enable --user 0 com.google.android.gms   # ALWAYS, even if the line above failed
```

If `set-device-owner` fails, `pm enable` puts everything back. Never uninstall Google Play services or clear its data.
