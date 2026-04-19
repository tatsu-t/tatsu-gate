package app;

import dev.gate.core.Config;
import dev.gate.core.Gate;
import dev.gate.core.Loadconfig;
import dev.gate.core.Logger;

public class Main {
    private static final Logger logger = new Logger(Main.class);

    public static void main(String[] args) throws Exception {
        Config config = Loadconfig.load();
        logger.info("Starting " + config.getName() + " in " + config.getEnv() + " mode");

        Gate gate = new Gate();
        gate.scan("app");
        gate.start(config.getPort());
    }
}