package dev.gate.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;

public class Loadconfig {

    private static final Logger logger = new Logger(Loadconfig.class);

    public static Config load() {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(
                tag -> tag.getClassName().equals(Config.class.getName())
        );

        Yaml yaml = new Yaml(new Constructor(Config.class, options));

        try (InputStream input = Loadconfig.class
                .getClassLoader()
                .getResourceAsStream("config.yml")) {

            if (input == null) {
                logger.info("config.yml not found, using defaults");
                return new Config();
            }

            return yaml.load(input);
        } catch (Exception e) {
            logger.warn("Failed to load config.yml: " + e.getMessage() + ", using defaults");
            return new Config();
        }
    }
}