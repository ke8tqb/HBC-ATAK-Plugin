package com.atakmap.android.hbc.plugin;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.atakmap.android.hbc.PttKeyer;

/**
 * Hardware PTT for Digirig-style interfaces (v0.25): keys the radio by
 * asserting RTS on the interface's Silicon Labs CP210x USB-UART, using
 * two raw vendor control transfers — no serial-driver library needed
 * (keeps the TAK third-party pipeline build dependency-free):
 *
 *   IFC_ENABLE (0x00, value 1)  — enable the UART function
 *   SET_MHS    (0x07)           — modem handshake: 0x0202 = RTS asserted
 *                                 (mask bit 9 + value bit 1), 0x0200 = released
 *
 * The Digirig Mobile's PTT transistor follows RTS, closing the radio's
 * mic/PTT circuit exactly like the mic button — no VOX attack or hang,
 * which is why hardwired-PTT setups can run Guard 300–500 ms and
 * VOX Lead 0 (see the ⓘ radio-defaults dialog).
 */
final class UsbPtt implements PttKeyer {

    private static final int VID_SILABS   = 0x10C4;
    private static final int PID_CP210X_A = 0xEA60;   // CP2102/CP2102N
    private static final int PID_CP210X_B = 0xEA61;   // CP2104 variants

    // C-Media CM108/CM119 sound-chip family (Digirig Lite): PTT is a GPIO
    // pin on the audio chip, driven through its HID interface.
    private static final int VID_CMEDIA   = 0x0D8C;
    private static final int VID_CMEDIA2  = 0x0C76;   // common clone VID

    private static final int REQTYPE_HOST_TO_DEVICE = 0x41; // vendor | interface
    private static final int CP210X_IFC_ENABLE = 0x00;
    private static final int CP210X_SET_MHS    = 0x07;
    private static final int MHS_RTS_ON  = 0x0202;
    private static final int MHS_RTS_OFF = 0x0200;

    // HID class SET_REPORT for the CM108 GPIO (Direwolf-compatible report:
    // {OR0=0, GPIO direction mask, GPIO data, 0} — GPIO3 = bit 0x04).
    private static final int HID_REQTYPE_OUT  = 0x21;   // class | interface
    private static final int HID_SET_REPORT   = 0x09;
    private static final int HID_REPORT_OUT_0 = 0x0200;
    private static final int CM108_GPIO3      = 0x04;

    static final int KIND_CP210X = 0;
    static final int KIND_CM108  = 1;

    /** Broadcast action for the USB permission result (plugin-internal). */
    static final String ACTION_USB_PERMISSION =
            "com.atakmap.android.hbc.USB_PERMISSION";

    /** Status sink (the plugin's Activity Log). */
    interface Status {
        void log(String message);
    }

    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private final Status status;
    private final int kind;
    private final Runnable onDead;
    final String name;
    private boolean dead = false;

    private UsbPtt(UsbDeviceConnection conn, UsbInterface iface, String name,
                   Status status, int kind, Runnable onDead) {
        this.conn = conn;
        this.iface = iface;
        this.name = name;
        this.status = status;
        this.kind = kind;
        this.onDead = onDead;
    }

    /**
     * Find and open the first CP210x on the bus. Returns null (with a
     * logged reason) when none is attached, the USB permission has not
     * been granted yet (a system dialog is raised — Stop/Start the radio
     * after granting), or the open fails. The caller falls back to
     * plain speaker/VOX behavior on null.
     */
    static UsbPtt open(Context ctx, Status status, Runnable onDead) {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um == null) {
                status.log("PTT: USB service unavailable");
                return null;
            }
            UsbDevice found = null;
            int kind = KIND_CP210X;
            for (UsbDevice d : um.getDeviceList().values()) {
                if (d.getVendorId() == VID_SILABS
                        && (d.getProductId() == PID_CP210X_A
                            || d.getProductId() == PID_CP210X_B)) {
                    found = d;
                    kind = KIND_CP210X;
                    break;
                }
            }
            if (found == null) {
                // Digirig Lite: GPIO PTT on the CM108-family audio chip
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (d.getVendorId() == VID_CMEDIA
                            || d.getVendorId() == VID_CMEDIA2) {
                        found = d;
                        kind = KIND_CM108;
                        break;
                    }
                }
            }
            if (found == null) {
                // Diagnostic: show exactly what Android enumerated so a
                // cable/handshake failure (empty list) is distinguishable
                // from a VID/PID the matcher does not know yet.
                StringBuilder sb = new StringBuilder();
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(String.format("%04X:%04X",
                            d.getVendorId(), d.getProductId()));
                    if (d.getProductName() != null)
                        sb.append(' ').append(d.getProductName());
                }
                status.log("PTT: no Digirig PTT device found (CP210x serial "
                        + "or CM108 GPIO) \u2014 using VOX/manual keying");
                status.log("PTT: USB devices visible: "
                        + (sb.length() == 0 ? "(none)" : sb.toString()));
                return null;
            }
            if (!um.hasPermission(found)) {
                try {
                    // The intent MUST be explicit (setPackage): since
                    // Android 14 a mutable PendingIntent around an implicit
                    // intent is rejected and the permission dialog would
                    // never appear.
                    Intent intent = new Intent(ACTION_USB_PERMISSION)
                            .setPackage(ctx.getPackageName());
                    int flags = Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_MUTABLE : 0;
                    um.requestPermission(found, PendingIntent.getBroadcast(
                            ctx, 0, intent, flags));
                    status.log("PTT: requesting USB access \u2014 approve the "
                            + "dialog (RTS engages automatically)");
                } catch (Throwable t) {
                    status.log("PTT: USB permission request failed: " + t);
                }
                return null;
            }
            UsbDeviceConnection c = um.openDevice(found);
            if (c == null) {
                status.log("PTT: USB PTT device open failed");
                return null;
            }
            UsbInterface target = null;
            if (kind == KIND_CP210X) {
                target = found.getInterface(0);
            } else {
                for (int i = 0; i < found.getInterfaceCount(); i++) {
                    if (found.getInterface(i).getInterfaceClass()
                            == android.hardware.usb.UsbConstants.USB_CLASS_HID) {
                        target = found.getInterface(i);
                        break;
                    }
                }
                if (target == null) {
                    c.close();
                    status.log("PTT: CM108 device has no HID interface \u2014 "
                            + "GPIO PTT unavailable");
                    return null;
                }
            }
            if (!c.claimInterface(target, true)) {
                c.close();
                status.log("PTT: USB PTT interface busy (another app?)");
                return null;
            }
            if (kind == KIND_CP210X) {
                c.controlTransfer(REQTYPE_HOST_TO_DEVICE, CP210X_IFC_ENABLE,
                        1, 0, null, 0, 500);
                c.controlTransfer(REQTYPE_HOST_TO_DEVICE, CP210X_SET_MHS,
                        MHS_RTS_OFF, 0, null, 0, 500);
            } else {
                cm108Gpio(c, target.getId(), false);
            }
            String nm = found.getProductName() != null
                    ? found.getProductName().toString()
                    : (kind == KIND_CP210X ? "CP210x" : "CM108");
            status.log(kind == KIND_CP210X
                    ? "PTT: USB RTS ready (" + nm + ")"
                    : "PTT: USB GPIO ready (" + nm + ")");
            return new UsbPtt(c, target, nm, status, kind, onDead);
        } catch (Throwable t) {
            status.log("PTT: USB PTT init failed: " + t);
            return null;
        }
    }

    /** CM108 GPIO3 output report: {0, direction mask, data, 0}. */
    private static int cm108Gpio(UsbDeviceConnection c, int ifaceId, boolean on) {
        byte[] rep = new byte[]{0x00, (byte) CM108_GPIO3,
                (byte) (on ? CM108_GPIO3 : 0x00), 0x00};
        return c.controlTransfer(HID_REQTYPE_OUT, HID_SET_REPORT,
                HID_REPORT_OUT_0, ifaceId, rep, rep.length, 200);
    }

    @Override
    public synchronized void key(boolean tx) {
        if (dead) return;
        int r;
        try {
            r = kind == KIND_CM108
                    ? cm108Gpio(conn, iface.getId(), tx)
                    : conn.controlTransfer(REQTYPE_HOST_TO_DEVICE, CP210X_SET_MHS,
                            tx ? MHS_RTS_ON : MHS_RTS_OFF, 0, null, 0, 200);
        } catch (Throwable t) {
            r = -1;
        }
        if (r < 0) {
            // The device dropped off the bus (or re-enumerated). Flag it
            // ONCE and let the plugin reopen a fresh connection — a failed
            // UNKEY otherwise risks a stuck transmitter.
            dead = true;
            status.log("PTT: keying write failed \u2014 reopening the Digirig "
                    + "connection" + (tx ? "" :
                    " (verify the radio is NOT stuck transmitting)"));
            if (onDead != null) {
                try { onDead.run(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Release the key line and the USB interface. Safe to call repeatedly. */
    synchronized void close() {
        try {
            if (kind == KIND_CM108)
                cm108Gpio(conn, iface.getId(), false);
            else
                conn.controlTransfer(REQTYPE_HOST_TO_DEVICE, CP210X_SET_MHS,
                        MHS_RTS_OFF, 0, null, 0, 200);
        } catch (Throwable ignored) {}
        try { conn.releaseInterface(iface); } catch (Throwable ignored) {}
        try { conn.close(); } catch (Throwable ignored) {}
    }
}
