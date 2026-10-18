package nl.hauntedmc.craftgpt.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelPresetConfigurationTest {
    @Test
    void codexPresetsRouteToRequestedModelAndEffort() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            assertCodexPresets(YamlConfiguration.loadConfiguration(reader));
        }
        String installed = System.getProperty("craftgpt.test.config");
        if (installed != null) {
            assertCodexPresets(YamlConfiguration.loadConfiguration(new java.io.File(installed)));
        }
    }

    private static void assertCodexPresets(YamlConfiguration config) {
        var expected = Map.of(
                "codex", List.of("gpt-6.1-sol", "high"),
                "codex-astra", List.of("gpt-6-astra", "high"),
                "codex-astra-xhigh", List.of("gpt-6-astra", "xhigh"),
                "codex-luna", List.of("gpt-6-luna", "high"));
        expected.forEach((alias, settings) -> {
            String root = "models." + alias;
            assertEquals("http://127.0.0.1:8766/v1/responses", config.getString(root + ".endpoint"), alias);
            var payload = config.getList(root + ".payload");
            assertNotNull(payload, alias);
            var merged = new java.util.HashMap<Object, Object>();
            payload.forEach(entry -> merged.putAll((Map<?, ?>) entry));
            assertEquals(settings.get(0), merged.get("model"), alias);
            assertEquals(settings.get(1), ((Map<?, ?>) merged.get("reasoning")).get("effort"), alias);
        });
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "craftgpt.test.config", matches = ".+")
    void installedPresetsHaveValidRoots() {
        var config = YamlConfiguration.loadConfiguration(new java.io.File(System.getProperty("craftgpt.test.config")));
        var models = config.getConfigurationSection("models");
        assertNotNull(models);
        assertFalse(models.contains("gpt-5"));
        assertTrue(models.contains("gpt-5-6"));
        for (String name : models.getKeys(false)) {
            assertNotNull(models.getString(name + ".endpoint"), name);
            assertNotNull(models.getList(name + ".payload"), name);
        }
    }
    @Test
    void defaultPresetsRemainIndependentAfterBukkitLoadAndSave() throws Exception {
        var resource = getClass().getResourceAsStream("/config.yml");
        assertNotNull(resource);
        YamlConfiguration loaded;
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            loaded = YamlConfiguration.loadConfiguration(reader);
        }
        var roundTrip = new YamlConfiguration();
        roundTrip.loadFromString(loaded.saveToString());
        var models = roundTrip.getConfigurationSection("models");
        assertNotNull(models);
        assertFalse(models.contains("gpt-5"), "No broken parent preset should be exposed");
        assertTrue(models.contains("gpt-5-6"));
        assertTrue(models.contains("gpt-5-4-mini"));
        for (String name : models.getKeys(false)) {
            assertFalse(name.contains("."), "Preset aliases must not be path expressions");
            assertNotNull(models.getString(name + ".endpoint"), name);
            List<?> payload = models.getList(name + ".payload");
            assertNotNull(payload, name);
            assertTrue(payload.stream().anyMatch(item -> item instanceof Map<?, ?> map && map.containsKey("model")), name);
        }
        var payload = models.getList("gpt-5-6.payload");
        assertNotNull(payload);
        assertTrue(payload.stream().anyMatch(item -> item instanceof Map<?, ?> map && "gpt-5.6".equals(map.get("model"))), "API model IDs must retain their original dots");
    }
}