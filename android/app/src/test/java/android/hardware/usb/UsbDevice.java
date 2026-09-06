package android.hardware.usb;

public class UsbDevice {
    private final UsbInterface[] interfaces;
    public UsbDevice(UsbInterface... interfaces) { this.interfaces = interfaces; }
    public int getInterfaceCount() { return interfaces.length; }
    public UsbInterface getInterface(int index) { return interfaces[index]; }
}
