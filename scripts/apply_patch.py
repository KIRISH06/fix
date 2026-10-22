#!/usr/bin/env python3
"""Apply the OfflinePay USSD/Accessibility fix to an apktool-decoded project.

usage: apply_patch.py <decoded_dir> <patch_dir>
Fails loudly (non-zero exit) if any expected anchor is missing, so a changed base APK
never gets silently half-patched.
"""
import glob
import os
import re
import shutil
import sys

dec, patch = sys.argv[1], sys.argv[2]
BRIDGE = "Lcom/offlinepay/fix/UssdBridge;"


def die(msg):
    print("PATCH ERROR:", msg)
    sys.exit(1)


def find_one(pattern):
    hits = glob.glob(os.path.join(dec, pattern), recursive=True)
    if len(hits) != 1:
        die("expected exactly one %s, found %d" % (pattern, len(hits)))
    return hits[0]


def rw(path, fn):
    with open(path, encoding="utf-8") as f:
        s = f.read()
    s2 = fn(s)
    if s2 == s:
        die("no change made to " + path)
    with open(path, "w", encoding="utf-8") as f:
        f.write(s2)


# ---------------------------------------------------------------- manifest + resources
SERVICE = """        <service android:name="com.offlinepay.fix.UssdAccessibilityService" android:exported="true" android:label="@string/ussd_service_label" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService"/>
            </intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/ussd_service"/>
        </service>
"""


def patch_manifest(s):
    if "UssdAccessibilityService" in s:
        die("manifest already patched")
    if "</application>" not in s:
        die("</application> not found in manifest")
    return s.replace("</application>", SERVICE + "    </application>", 1)


rw(os.path.join(dec, "AndroidManifest.xml"), patch_manifest)

os.makedirs(os.path.join(dec, "res", "xml"), exist_ok=True)
shutil.copy(os.path.join(patch, "res", "xml", "ussd_service.xml"), os.path.join(dec, "res", "xml", "ussd_service.xml"))
os.makedirs(os.path.join(dec, "res", "values"), exist_ok=True)
shutil.copy(os.path.join(patch, "res", "values", "ussd_strings.xml"), os.path.join(dec, "res", "values", "ussd_strings.xml"))

# ---------------------------------------------------------------- UssdClient.smali
ussd_client = find_one("smali*/com/offlinepay/app/UssdClient.smali")


def patch_ussd_client(s):
    # 1) constructor: remember context + SIM subscription id
    ctor_anchor = "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
    if s.count(ctor_anchor) != 1:
        die("UssdClient.<init> anchor not found")
    s = s.replace(
        ctor_anchor,
        ctor_anchor + "\n    invoke-static {p1, p2}, %s->init(Landroid/content/Context;I)V\n" % BRIDGE,
    )

    # 2) send(): replace the whole body with a call into the bridge
    m = re.search(r"\.method public final send\(Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;\)V.*?\n\.end method\n", s, re.S)
    if not m:
        die("UssdClient.send not found")
    new_send = (
        ".method public final send(Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V\n"
        "    .locals 0\n"
        "\n"
        "    invoke-static {p1, p2, p3}, %s->send(Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V\n"
        "\n"
        "    return-void\n"
        ".end method\n" % BRIDGE
    )
    return s[: m.start()] + new_send + s[m.end():]


rw(ussd_client, patch_ussd_client)

# ---------------------------------------------------------------- MainActivity.smali
main_act = find_one("smali*/com/offlinepay/app/MainActivity.smali")


def patch_main(s):
    anchor = "    invoke-super {p0}, Landroidx/activity/ComponentActivity;->onStart()V\n"
    if s.count(anchor) != 1:
        die("MainActivity.onStart anchor not found")
    return s.replace(
        anchor,
        anchor + "\n    invoke-static {p0}, %s->promptIfNeeded(Landroid/app/Activity;)V\n" % BRIDGE,
    )


rw(main_act, patch_main)
print("patch applied OK")
