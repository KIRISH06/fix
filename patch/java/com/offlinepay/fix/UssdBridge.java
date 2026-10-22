package com.offlinepay.fix;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kotlin.jvm.functions.Function1;

/**
 * Replaces the old TelephonyManager.sendUssdRequest() based USSD client.
 *
 * Why: sendUssdRequest() opens a NEW USSD session on every call, so after "*99#" the
 * follow-up replies ("1", amount, UPI id, PIN ...) never reach the same bank menu and
 * the payment always failed. A real multi-step USSD session needs the system USSD dialog
 * to stay open and be answered inside it. That is what this bridge does through an
 * AccessibilityService: dial the code, read the dialog text, type the reply into the
 * dialog's input box and press its Send button.
 *
 * Everything here runs on the main thread.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class UssdBridge {

    private static final long RESPONSE_TIMEOUT_MS = 35_000L;
    private static final long SAME_TEXT_GUARD_MS = 2_500L;
    private static final long CLICK_DELAY_MS = 250L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Context appCtx;
    private static volatile int subId = -1;

    // ---- session state (main thread only) ----
    private static boolean awaiting;          // a response from the bank is expected
    private static boolean dialogOpen;        // a USSD dialog with an input box is (probably) on screen
    private static Function1 pendingOk;
    private static Function1 pendingFail;
    private static String lastText;
    private static long repliedAt;
    private static boolean shownPromptThisProcess;
    private static Runnable timeoutTask;
    private static Runnable recheckTask;

    private UssdBridge() {
    }

    // ------------------------------------------------------------------ hooks called from patched app code

    /** Called from the UssdClient constructor (patched). */
    public static void init(Context ctx, int simSubId) {
        appCtx = ctx.getApplicationContext();
        subId = simSubId;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                resetSession(true);
            }
        });
    }

    /** Called from UssdClient.send (patched). */
    public static void send(final String text, final Function1 onOk, final Function1 onFail) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    doSend(text, onOk, onFail);
                } catch (Throwable t) {
                    fail(onFail, "USSD error: " + String.valueOf(t.getMessage()));
                }
            }
        });
    }

    /** Called from MainActivity.onStart (patched): ask for the Accessibility permission. */
    public static void promptIfNeeded(final Activity a) {
        try {
            if (isServiceEnabled(a) || shownPromptThisProcess) {
                return;
            }
            SharedPreferences sp = a.getSharedPreferences("offlinepay", Context.MODE_PRIVATE);
            if (!sp.getBoolean("onboarded", false)) {
                return; // let the normal onboarding finish first
            }
            if (a.checkSelfPermission("android.permission.CALL_PHONE") != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            shownPromptThisProcess = true;
            new AlertDialog.Builder(a)
                    .setTitle("Accessibility permission zaroori hai")
                    .setMessage("Payment (*99#) chalane ke liye \"OfflinePay USSD Helper\" ko "
                            + "Accessibility me ON karein.\n\n"
                            + "Settings > Accessibility > Installed apps / Downloaded apps > "
                            + "OfflinePay USSD Helper > ON.\n\n"
                            + "Agar toggle grey (disabled) hai: Phone Settings > Apps > OfflinePay > "
                            + "upar kone me 3 dots (⋮) > \"Allow restricted settings\", phir dobara try karein.\n\n"
                            + "एक्सेसिबिलिटी में \"OfflinePay USSD Helper\" को ON करें।")
                    .setPositiveButton("Settings kholein", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            openAccessibilitySettings(a);
                        }
                    })
                    .setNegativeButton("Baad me", null)
                    .show();
        } catch (Throwable ignored) {
            // never break app start-up
        }
    }

    // ------------------------------------------------------------------ sending

    private static void doSend(String text, Function1 onOk, Function1 onFail) {
        Context c = appCtx;
        if (c == null) {
            fail(onFail, "App ready nahi hai, dobara try karein.");
            return;
        }
        UssdAccessibilityService svc = UssdAccessibilityService.instance;
        if (svc == null) {
            if (isServiceEnabled(c)) {
                fail(onFail, "Accessibility service start ho rahi hai. 2-3 second baad dobara try karein.");
            } else {
                openAccessibilitySettings(c);
                toast(c, "OfflinePay USSD Helper ko ON karein");
                fail(onFail, "Accessibility permission chahiye. Settings > Accessibility > "
                        + "OfflinePay USSD Helper ko ON karein, phir dobara pay karein. "
                        + "(Toggle grey ho to: App info > 3 dots > Allow restricted settings)");
            }
            return;
        }

        cancelTimers();
        pendingOk = onOk;
        pendingFail = onFail;
        awaiting = true;
        armTimeout();

        if (isDialString(text)) {
            // brand new session: close anything left over, then dial
            lastText = null;
            if (dialogOpen) {
                dismissCurrentDialog(svc);
            }
            dialogOpen = false;
            if (!dial(c, text)) {
                // dial() already reported the failure
                return;
            }
        } else {
            if (!dialogOpen) {
                failPending("USSD session band ho chuka hai. Dobara shuru karein.");
                return;
            }
            lastText = currentText(svc);
            repliedAt = System.currentTimeMillis();
            if (!reply(svc, text)) {
                failPending("USSD popup me jawab nahi likh paye. Dobara try karein.");
                return;
            }
            scheduleRecheck(svc);
        }
    }

    private static boolean isDialString(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.length() >= 3 && (t.charAt(0) == '*' || t.charAt(0) == '#') && t.endsWith("#");
    }

    private static boolean dial(Context c, String code) {
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(code.trim())));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PhoneAccountHandle h = findAccount(c, subId);
            if (h != null) {
                i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
            }
            c.startActivity(i);
            return true;
        } catch (SecurityException e) {
            failPending("Call (Phone) permission chahiye.");
            return false;
        } catch (Throwable t) {
            failPending("USSD shuru nahi ho paya: " + String.valueOf(t.getMessage()));
            return false;
        }
    }

    private static PhoneAccountHandle findAccount(Context c, int sub) {
        if (sub < 0) return null;
        try {
            TelecomManager tel = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tel == null || tm == null) return null;
            List<PhoneAccountHandle> list = tel.getCallCapablePhoneAccounts();
            if (list == null) return null;
            for (PhoneAccountHandle h : list) {
                if (Build.VERSION.SDK_INT >= 30) {
                    if (tm.getSubscriptionId(h) == sub) return h;
                } else {
                    String id = h.getId();
                    android.telephony.SubscriptionManager sm =
                            (android.telephony.SubscriptionManager) c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    android.telephony.SubscriptionInfo si = sm == null ? null : sm.getActiveSubscriptionInfo(sub);
                    String icc = si == null ? null : si.getIccId();
                    if (id != null && icc != null && (id.equals(icc) || icc.startsWith(id) || id.startsWith(icc))) {
                        return h;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall back to the default SIM
        }
        return null;
    }

    // ------------------------------------------------------------------ reading the dialog

    /** Parsed view of the system USSD dialog. */
    private static final class Dialog {
        String message = "";
        AccessibilityNodeInfo edit;
        final List<AccessibilityNodeInfo> buttons = new ArrayList<AccessibilityNodeInfo>();
    }

    static void onEvent(AccessibilityService svc, AccessibilityEvent e) {
        if (e == null || (!awaiting && !dialogOpen)) return;
        int type = e.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        if (!awaiting) return; // nothing expected right now
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !isUssdPackage(pkg.toString())) return;
        process(svc, false);
    }

    private static void process(AccessibilityService svc, boolean forced) {
        if (!awaiting) return;
        Dialog d = readDialog(svc);
        if (d == null || d.message.length() == 0) return;
        if (isProgressMessage(d.message)) return; // "USSD code running..." spinner, not the bank's answer

        if (!forced && lastText != null && d.message.equals(lastText)
                && System.currentTimeMillis() - repliedAt < SAME_TEXT_GUARD_MS) {
            return; // old dialog still on screen after our reply
        }
        deliver(svc, d);
    }

    private static void deliver(AccessibilityService svc, Dialog d) {
        final Function1 ok = pendingOk;
        final String msg = d.message;
        awaiting = false;
        pendingOk = null;
        pendingFail = null;
        cancelTimers();
        lastText = msg;

        if (d.edit == null) {
            // Final message (no input box): hand it over, then close the dialog.
            dialogOpen = false;
            clickDismiss(d);
        } else {
            dialogOpen = true;
        }
        if (ok != null) {
            ok.invoke(msg);
        }
    }

    private static String currentText(AccessibilityService svc) {
        Dialog d = readDialog(svc);
        return d == null ? null : d.message;
    }

    private static Dialog readDialog(AccessibilityService svc) {
        List<AccessibilityNodeInfo> roots = new ArrayList<AccessibilityNodeInfo>();
        try {
            AccessibilityNodeInfo active = svc.getRootInActiveWindow();
            if (active != null) roots.add(active);
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> ws = svc.getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) roots.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        Context c = appCtx;
        String own = c == null ? "" : c.getPackageName();
        for (AccessibilityNodeInfo r : roots) {
            CharSequence p = r.getPackageName();
            if (p == null) continue;
            String ps = p.toString();
            if (ps.equals(own) || !isUssdPackage(ps)) continue;
            Dialog d = new Dialog();
            StringBuilder sb = new StringBuilder();
            walk(r, d, sb, 0);
            d.message = sb.toString().trim();
            if (d.message.length() > 0 || d.edit != null) {
                return d;
            }
        }
        return null;
    }

    private static void walk(AccessibilityNodeInfo n, Dialog d, StringBuilder sb, int depth) {
        if (n == null || depth > 25) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        CharSequence txt = n.getText();

        if (cls.contains("EditText") || (n.isEditable() && cls.length() > 0)) {
            if (d.edit == null) d.edit = n;
        } else if (cls.contains("Button") || (n.isClickable() && cls.length() > 0 && n.getChildCount() == 0
                && cls.contains("Text") && isButtonLabel(txt))) {
            if (txt != null && txt.length() > 0) d.buttons.add(n);
        } else if (cls.contains("TextView") && txt != null && txt.length() > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(txt);
        }
        int cc = n.getChildCount();
        for (int i = 0; i < cc; i++) {
            walk(n.getChild(i), d, sb, depth + 1);
        }
    }

    private static boolean isButtonLabel(CharSequence t) {
        if (t == null) return false;
        String s = t.toString().trim().toLowerCase(Locale.ROOT);
        return s.length() <= 12 && (isPositive(s) || isNegative(s));
    }

    private static boolean isProgressMessage(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        return s.contains("ussd code running") || s.contains("running ussd")
                || s.contains("ussd running") || s.contains("please wait");
    }

    private static boolean isUssdPackage(String p) {
        String s = p.toLowerCase(Locale.ROOT);
        return s.contains("phone") || s.contains("telecom") || s.contains("dialer")
                || s.contains("incallui") || s.contains("telephony") || s.contains("ussd");
    }

    // ------------------------------------------------------------------ typing into the dialog

    private static boolean reply(AccessibilityService svc, String text) {
        final Dialog d = readDialog(svc);
        if (d == null || d.edit == null) return false;

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        d.edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean set = d.edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) return false;

        final AccessibilityNodeInfo send = pickSendButton(d.buttons);
        if (send == null) return false; // never press BACK here: it would cancel the session
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }, CLICK_DELAY_MS);
        return true;
    }

    private static AccessibilityNodeInfo pickSendButton(List<AccessibilityNodeInfo> buttons) {
        AccessibilityNodeInfo lastNonNegative = null;
        for (AccessibilityNodeInfo b : buttons) {
            String s = label(b);
            if (isPositive(s)) return b;
            if (!isNegative(s)) lastNonNegative = b;
        }
        if (lastNonNegative != null) return lastNonNegative;
        return buttons.isEmpty() ? null : buttons.get(buttons.size() - 1);
    }

    private static void clickDismiss(Dialog d) {
        AccessibilityNodeInfo target = null;
        for (AccessibilityNodeInfo b : d.buttons) {
            String s = label(b);
            if (isPositive(s) || isNegative(s)) {
                target = b;
                break;
            }
        }
        if (target == null && !d.buttons.isEmpty()) target = d.buttons.get(0);
        final AccessibilityNodeInfo t = target;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (t != null) {
                    t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else {
                    UssdAccessibilityService s = UssdAccessibilityService.instance;
                    if (s != null) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                }
            }
        }, 600L);
    }

    private static void dismissCurrentDialog(AccessibilityService svc) {
        try {
            Dialog d = readDialog(svc);
            if (d != null) clickDismiss(d);
        } catch (Throwable ignored) {
        }
    }

    private static String label(AccessibilityNodeInfo b) {
        CharSequence t = b.getText();
        return t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isPositive(String s) {
        return s.equals("send") || s.equals("ok") || s.equals("reply") || s.equals("submit")
                || s.equals("yes") || s.equals("dismiss") || s.equals("continue") || s.equals("next")
                || s.contains("भेज") /* भेज */ || s.contains("ठीक") /* ठीक */
                || s.contains("जवाब") /* जवाब */ || s.contains("सबमिट");
    }

    private static boolean isNegative(String s) {
        return s.equals("cancel") || s.equals("close") || s.equals("no") || s.equals("exit")
                || s.contains("रद्द") /* रद्द */ || s.contains("बंद") /* बंद */
                || s.contains("कैंसल");
    }

    // ------------------------------------------------------------------ timers / failures

    private static void armTimeout() {
        timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (awaiting) {
                    failPending("Bank se jawab nahi aaya. Signal check karke dobara try karein.");
                }
            }
        };
        MAIN.postDelayed(timeoutTask, RESPONSE_TIMEOUT_MS);
    }

    private static void scheduleRecheck(final AccessibilityService svc) {
        recheckTask = new Runnable() {
            @Override
            public void run() {
                if (awaiting) {
                    try {
                        process(svc, true);
                    } catch (Throwable ignored) {
                    }
                    if (awaiting) {
                        // keep polling lightly until the timeout fires
                        MAIN.postDelayed(this, 1500L);
                    }
                }
            }
        };
        MAIN.postDelayed(recheckTask, SAME_TEXT_GUARD_MS + 100L);
    }

    private static void cancelTimers() {
        if (timeoutTask != null) MAIN.removeCallbacks(timeoutTask);
        if (recheckTask != null) MAIN.removeCallbacks(recheckTask);
        timeoutTask = null;
        recheckTask = null;
    }

    private static void failPending(String msg) {
        Function1 f = pendingFail;
        awaiting = false;
        pendingOk = null;
        pendingFail = null;
        cancelTimers();
        if (dialogOpen) {
            UssdAccessibilityService s = UssdAccessibilityService.instance;
            if (s != null) dismissCurrentDialog(s);
            dialogOpen = false;
        }
        fail(f, msg);
    }

    private static void fail(Function1 f, String msg) {
        if (f != null) f.invoke(msg);
    }

    private static void resetSession(boolean closeDialog) {
        cancelTimers();
        awaiting = false;
        pendingOk = null;
        pendingFail = null;
        lastText = null;
        if (closeDialog && dialogOpen) {
            UssdAccessibilityService s = UssdAccessibilityService.instance;
            if (s != null) dismissCurrentDialog(s);
        }
        dialogOpen = false;
    }

    // ------------------------------------------------------------------ accessibility helpers

    static boolean isServiceEnabled(Context c) {
        if (UssdAccessibilityService.instance != null) return true;
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.length() == 0) return false;
            ComponentName me = new ComponentName(c.getPackageName(), UssdAccessibilityService.class.getName());
            for (String part : enabled.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (me.equals(cn)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static void openAccessibilitySettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private static void toast(Context c, String m) {
        try {
            Toast.makeText(c, m, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
