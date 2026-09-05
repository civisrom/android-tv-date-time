package android.content;

// Minimal NSD boundary fake. This does not emulate Android framework behavior.
public class Context {
    private final Object service;
    public Context(Object service) { this.service = service; }
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return service; }
}
