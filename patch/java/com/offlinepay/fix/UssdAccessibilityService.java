package com.offlinepay.fix;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Thin service: it only forwards events to {@link UssdBridge}, which does nothing
 * unless a *99# payment/balance request is currently in progress.
 */
public class UssdAccessibilityService extends AccessibilityService {

    static volatile UssdAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            UssdBridge.onEvent(this, event);
        } catch (Throwable ignored) {
            // never crash the accessibility service
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
