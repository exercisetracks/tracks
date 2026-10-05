// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service;

/**
 * Shim for Gadgetbridge's Sleep as Android integration.
 *
 * <p>Tracks does not integrate with Sleep as Android — sleep data reaches the
 * server inside the FIT files we upload — so no instance of this is ever
 * created: {@code AbstractDeviceSupport.getSleepAsAndroidSender()} returns null,
 * which is upstream's own "not configured" signal, and every call site in
 * {@code CommunicatorV2} null-checks before use.
 *
 * <p>The methods still have to exist for the vendored code to compile — a null
 * check guards the call at runtime, not the symbol at compile time — so they
 * throw rather than silently doing nothing. They are provably unreachable while
 * the getter returns null, and if that ever changes, an exception naming this
 * class is a far better outcome than sleep data being quietly dropped.
 */
public final class SleepAsAndroidSender {

    private SleepAsAndroidSender() {}

    private static UnsupportedOperationException notIntegrated() {
        return new UnsupportedOperationException(
                "Sleep as Android is not integrated in Tracks; this sender should never exist");
    }

    public void onHrChanged(final float value, final float ignored) {
        throw notIntegrated();
    }

    public void onAccelChanged(final float x, final float y, final float z) {
        throw notIntegrated();
    }

    public void sendExtra(final Float hr, final Float extraDataRR, final Float spo2,
                          final Float sdnn, final Long sdnnTimestamp) {
        throw notIntegrated();
    }
}
