// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.impl;

import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Shim for Gadgetbridge's device model — the most load-bearing of the shims,
 * referenced from 25 places in the vendored code.
 *
 * Upstream this carries the device's identity, its connection state, battery
 * and firmware details, a busy flag, and a bag of vendor-specific extras, and
 * it broadcasts an intent whenever anything changes so the UI can redraw.
 *
 * Here it is a plain state holder. The broadcast becomes a listener callback:
 * Tracks surfaces connection state through {@code DeviceIntegration.connection}
 * as a Flow, so an Android broadcast would be a second, weaker channel carrying
 * the same information — and one that leaks device addresses to any app that
 * registers for it.
 *
 * Only the surface the vendored code actually calls is implemented, which is
 * why this is a few hundred lines rather than upstream's thousand.
 */
public class GBDevice implements Parcelable {

    private static final String TAG = "GBDevice";

    public static final short RSSI_UNKNOWN = 0;

    public static final String ACTION_DEVICE_CHANGED =
            "nodomain.freeyourgadget.gadgetbridge.gbdevice.action.device_changed";

    /**
     * What changed about a device, so a listener can skip work it does not care
     * about. Upstream uses it to avoid redrawing everything on every RSSI tick.
     */
    public enum DeviceUpdateSubject {
        UNKNOWN,
        NOTHING,
        CONNECTION_STATE,
        DEVICE_STATE,
        BATTERY_LEVEL,
    }

    public enum State {
        NOT_CONNECTED,
        WAITING_FOR_RECONNECT,
        WAITING_FOR_SCAN,
        CONNECTING,
        CONNECTED,
        INITIALIZING,
        AUTHENTICATION_REQUIRED,
        AUTHENTICATING,
        INITIALIZED;

        public boolean equalsOrHigherThan(State other) {
            return ordinal() >= other.ordinal();
        }
    }

    private final String address;
    private String name;
    private String model;
    private String firmwareVersion;
    private String firmwareVersion2;
    private volatile State state = State.NOT_CONNECTED;
    private String busyTask;
    private int bondState = BluetoothDevice.BOND_NONE;
    private final Map<String, Object> extraInfo = new HashMap<>();

    /** Replaces Gadgetbridge's device-update broadcast. See the class note. */
    public interface StateListener {
        void onDeviceStateChanged(GBDevice device);
    }

    private volatile StateListener listener;

    public GBDevice(String address, String name, String model) {
        this.address = address;
        this.name = name;
        this.model = model;
    }

    public void setStateListener(StateListener listener) {
        this.listener = listener;
    }

    public String getAddress() { return address; }

    /** Gadgetbridge keys per-device preferences on this; the address is stable. */
    public String getId() { return address; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String v) { this.firmwareVersion = v; }

    public String getFirmwareVersion2() { return firmwareVersion2; }
    public void setFirmwareVersion2(String v) { this.firmwareVersion2 = v; }

    public State getState() { return state; }

    public void setState(State newState) {
        this.state = newState;
        // Upstream clears the busy flag on disconnect; without this a device
        // that drops mid-transfer stays "busy" forever and refuses the next one.
        if (newState.ordinal() < State.CONNECTED.ordinal()) {
            busyTask = null;
        }
    }

    /** Upstream distinguishes an update-only state change; the effect is the same here. */
    public void setUpdateState(State newState, android.content.Context context) {
        setState(newState);
        sendDeviceUpdateIntent(context);
    }

    public boolean isConnected() { return state.ordinal() >= State.CONNECTED.ordinal(); }

    public boolean isInitialized() { return state == State.INITIALIZED; }

    public boolean isConnecting() { return state == State.CONNECTING; }

    public boolean isBusy() { return busyTask != null; }

    public String getBusyTask() { return busyTask; }

    public void setBusyTask(String task) {
        if (task == null) {
            Log.w(TAG, "setBusyTask called with null; ignoring");
            return;
        }
        if (busyTask != null) {
            Log.w(TAG, "attempted '" + task + "' while busy with '" + busyTask + "'");
        }
        busyTask = task;
    }

    /**
     * Upstream takes a string resource id so the busy state can be shown in its
     * UI. Tracks resolves it to text for logging and nothing more.
     */
    public void setBusyTask(int taskResId, android.content.Context context) {
        setBusyTask(context != null ? context.getString(taskResId) : String.valueOf(taskResId));
    }

    public void unsetBusyTask() {
        if (busyTask == null) {
            Log.w(TAG, "unsetBusyTask with nothing in progress");
            return;
        }
        busyTask = null;
    }

    public int getBondState() { return bondState; }
    public void setBondState(int bondState) { this.bondState = bondState; }

    /**
     * Upstream broadcasts an intent so the UI redraws. Tracks reports state
     * through a Flow instead, so this notifies the listener the integration
     * installed and sends nothing system-wide.
     */
    public void sendDeviceUpdateIntent(android.content.Context context) {
        StateListener l = listener;
        if (l != null) {
            l.onDeviceStateChanged(this);
        }
    }

    public void sendDeviceUpdateIntent(android.content.Context context, boolean includeDetails) {
        sendDeviceUpdateIntent(context);
    }

    public void sendDeviceUpdateIntent(android.content.Context context,
                                       DeviceUpdateSubject subject) {
        sendDeviceUpdateIntent(context);
    }

    public Object getExtraInfo(String key) { return extraInfo.get(key); }

    public void setExtraInfo(String key, Object value) {
        if (value == null) extraInfo.remove(key); else extraInfo.put(key, value);
    }

    private final nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator coordinator =
            new nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator();

    /**
     * Upstream returns the coordinator describing this device's capabilities.
     * Tracks declares capabilities in DeviceCapabilities instead; only two
     * connection-tuning methods survive the vendoring boundary, so this returns
     * a single shared shim rather than a per-model implementation.
     */
    public nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator getDeviceCoordinator() {
        return coordinator;
    }

    @Override
    public String toString() {
        return "GBDevice{" + name + " (" + address + ") " + state + "}";
    }

    // ── Parcelable ───────────────────────────────────────────────────────────
    // Some vendored signatures pass a GBDevice through an Intent.

    protected GBDevice(Parcel in) {
        address = in.readString();
        name = in.readString();
        model = in.readString();
        firmwareVersion = in.readString();
        state = State.values()[in.readInt()];
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(address);
        dest.writeString(name);
        dest.writeString(model);
        dest.writeString(firmwareVersion);
        dest.writeInt(state.ordinal());
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GBDevice> CREATOR = new Creator<GBDevice>() {
        @Override public GBDevice createFromParcel(Parcel in) { return new GBDevice(in); }
        @Override public GBDevice[] newArray(int size) { return new GBDevice[size]; }
    };
}
