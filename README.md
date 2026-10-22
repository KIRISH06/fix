# OfflinePay – fixed build

## Kya problem thi
1. App `*99#` ke baad har step (menu number, amount, UPI ID, PIN) `TelephonyManager.sendUssdRequest()` se bhejta tha.
   Yeh API har baar **nayi USSD session** kholti hai, isliye bank ka menu aage badhta hi nahi tha → payment fail.
2. APK me Accessibility ka sirf config file bacha tha; **service class aur manifest entry maujood hi nahi thi**,
   isliye Android settings me koi Accessibility permission dikhti hi nahi thi.

## Kya fix kiya
* `OfflinePay USSD Helper` naam ki AccessibilityService add ki (`patch/java/com/offlinepay/fix/`).
  Yeh `*99#` dial karti hai, bank ke USSD popup ka text padhti hai, input box me jawab likhkar Send dabati hai,
  aur final message ke baad popup band kar deti hai. Yeh sirf tab kaam karti hai jab payment/balance chal raha ho.
* `UssdClient.send()` ab is service ko use karta hai (smali patch).
* App khulne par (onboarding ke baad, Phone permission milte hi) Accessibility ON karne ka popup aata hai;
  aur payment dabane par agar service OFF ho to Accessibility Settings khud khul jaati hai.
* UI, PIN lock, scanner, history – sab original APK ka hi hai, kuch nahi badla.

## GitHub par APK banana
1. GitHub par naya repo banao aur is folder ki saari files upload/push karo (`base/app-debug.apk` bhi).
2. Repo > **Actions** > "Build OfflinePay APK" > **Run workflow** (push par apne aap bhi chalega).
3. Complete hone par: Actions run > **Artifacts** > `OfflinePay-fixed-apk`, ya repo ke **Releases** se `OfflinePay-fixed.apk` download karo.

## Phone me install karne ke baad
1. Purana OfflinePay **uninstall** karo (signing key alag hai, warna install nahi hoga).
2. App kholo, onboarding me Phone/Camera/Notification permission do.
3. Accessibility popup aayega → "Settings kholein" → **OfflinePay USSD Helper** → ON.
4. **Android 13/14+ par toggle grey ho to:** Settings > Apps > OfflinePay > upar 3 dots (⋮) > **Allow restricted settings**, phir dobara Accessibility me ON karo.
5. Ek chhoti rakam se test payment karo.

## Local build (optional)
`bash scripts/build.sh` (Java 17, Android SDK build-tools aur platform chahiye).
