// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge;

/**
 * Shim for Gadgetbridge's environment probe.
 *
 * <p>Upstream distinguishes a device build from a Robolectric test run, because
 * some of its code cannot run under test. Tracks' vendored slice is exercised
 * on-device, so this always reports a real device.
 */
public final class GBEnvironment {
    private static final GBEnvironment DEVICE = new GBEnvironment();

    private GBEnvironment() {}

    public static GBEnvironment env() { return DEVICE; }

    public boolean isLocalTest() { return false; }

    public boolean isDeviceTest() { return false; }
}
