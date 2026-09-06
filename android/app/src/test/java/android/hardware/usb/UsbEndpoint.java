package android.hardware.usb;

/** JVM boundary fixture, not an Android USB emulator. */
public class UsbEndpoint {
    private final int type, direction, packetSize;
    public UsbEndpoint(int type, int direction, int packetSize) {
        this.type = type; this.direction = direction; this.packetSize = packetSize;
    }
    public int getType() { return type; }
    public int getDirection() { return direction; }
    public int getMaxPacketSize() { return packetSize; }
}
