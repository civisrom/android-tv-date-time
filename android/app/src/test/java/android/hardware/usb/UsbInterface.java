package android.hardware.usb;

public class UsbInterface {
    private final int cls, sub, protocol;
    private final UsbEndpoint[] endpoints;
    public UsbInterface(int cls, int sub, int protocol, UsbEndpoint... endpoints) {
        this.cls = cls; this.sub = sub; this.protocol = protocol; this.endpoints = endpoints;
    }
    public int getInterfaceClass() { return cls; }
    public int getInterfaceSubclass() { return sub; }
    public int getInterfaceProtocol() { return protocol; }
    public int getEndpointCount() { return endpoints.length; }
    public UsbEndpoint getEndpoint(int index) { return endpoints[index]; }
}
