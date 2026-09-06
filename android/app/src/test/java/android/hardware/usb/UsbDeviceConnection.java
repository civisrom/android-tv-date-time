package android.hardware.usb;

import java.util.ArrayList;
import java.util.List;

public class UsbDeviceConnection {
    public final List<Integer> writes = new ArrayList<>();
    public int closed, released, reads, maxRead = Integer.MAX_VALUE;
    public boolean fail;
    public boolean releaseInterface(UsbInterface intf) { released++; return true; }
    public void close() { closed++; }
    public int bulkTransfer(UsbEndpoint endpoint, byte[] data, int offset, int length, int timeout) {
        if (timeout <= 0) throw new AssertionError("Unbounded USB wait");
        if (fail) return -1;
        if (endpoint.getDirection() == 128) {
            reads++;
            int count = Math.min(length, maxRead);
            for (int i = 0; i < count; i++) data[offset + i] = (byte)(offset + i);
            return count;
        }
        writes.add(length);
        return length;
    }
}
