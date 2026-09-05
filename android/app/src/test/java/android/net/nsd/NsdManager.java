package android.net.nsd;

import java.util.*;
import java.util.concurrent.Executor;

// Callbacks are deliberately delivered by tests, including after unregister requests.
public class NsdManager {
    public final Map<String, DiscoveryListener> discovery = new HashMap<>();
    public final List<ServiceInfoCallback> callbacks = new ArrayList<>();
    public final List<ServiceInfoCallback> unregistered = new ArrayList<>();
    public final List<ResolveListener> resolves = new ArrayList<>();
    public void discoverServices(String type, int protocol, DiscoveryListener listener) {
        discovery.put(type, listener);
    }
    public void stopServiceDiscovery(DiscoveryListener listener) {}
    public void resolveService(NsdServiceInfo info, ResolveListener listener) { resolves.add(listener); }
    public void registerServiceInfoCallback(NsdServiceInfo info, Executor executor, ServiceInfoCallback cb) {
        callbacks.add(cb);
    }
    public void unregisterServiceInfoCallback(ServiceInfoCallback cb) { unregistered.add(cb); }
    public interface DiscoveryListener {
        void onDiscoveryStarted(String type);
        void onStartDiscoveryFailed(String type, int code);
        void onDiscoveryStopped(String type);
        void onStopDiscoveryFailed(String type, int code);
        void onServiceFound(NsdServiceInfo info);
        void onServiceLost(NsdServiceInfo info);
    }
    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo info, int code);
        void onServiceResolved(NsdServiceInfo info);
    }
    public interface ServiceInfoCallback {
        void onServiceInfoCallbackRegistrationFailed(int code);
        void onServiceUpdated(NsdServiceInfo info);
        void onServiceLost();
        void onServiceInfoCallbackUnregistered();
    }
}
