package app;

import dev.gate.core.Gate;

public class Main {
    public static void main(String[] args) throws Exception {
        Gate gate = new Gate();
        gate.cors("http://localhost:3000");
        gate.scan("app");
        gate.start(8080);
    }
}
