package android.net.nsd;

import java.net.InetAddress;
import java.util.List;

public class NsdServiceInfo {
    private final String name, type;
    public NsdServiceInfo(String name, String type) { this.name = name; this.type = type; }
    public String getServiceName() { return name; }
    public String getServiceType() { return type; }
    public int getPort() { return 37105; }
    public List<InetAddress> getHostAddresses() {
        try { return List.of(InetAddress.getByAddress(new byte[]{127, 0, 0, 1})); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    public InetAddress getHost() { return getHostAddresses().get(0); }
}
