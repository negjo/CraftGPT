package nl.hauntedmc.craftgpt.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageBundleTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('\u00A7')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    @Test
    void rendersConfiguredPrefixAndMessages() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.options().pathSeparator('/');
        config.loadFromString("""
                prefix: "<color:#475569>[</color><gradient:#f59e0b:#38bdf8>CraftGPT</gradient><color:#475569>]</color> "
                messages:
                  "info.structure": "<color:#94a3b8>CraftGPT is generating: </color><color:#38bdf8><param></color>"
                """);

        MessageBundle bundle = new MessageBundle(config);

        Component prefix = bundle.prefix();
        Component message = bundle.render("info.structure", "windmill");

        assertEquals("[CraftGPT] ", PLAIN.serialize(prefix));
        assertEquals("CraftGPT is generating: windmill", PLAIN.serialize(message));
        assertEquals(TextColor.color(0x475569), prefix.children().get(0).color());
        assertEquals(TextColor.color(0x94a3b8), message.children().get(0).color());
        assertEquals(TextColor.color(0x38bdf8), message.children().get(1).color());
    }

    @Test
    void supportsLegacyAmpersandFormattingForExistingMessageFiles() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.options().pathSeparator('/');
        config.loadFromString("""
                prefix: '&8[&6Craft&bGPT&8] '
                messages:
                  "info.structure": '&7CraftGPT is generating: &b{param}'
                """);

        MessageBundle bundle = new MessageBundle(config);

        assertEquals("§8[§6Craft§bGPT§8] ", LEGACY.serialize(bundle.prefix()));
        assertEquals("§7CraftGPT is generating: §bwindmill", LEGACY.serialize(bundle.render("info.structure", "windmill")));
    }

    @Test
    void fallsBackCleanlyForMissingMessages() {
        MessageBundle bundle = new MessageBundle(new YamlConfiguration());

        assertEquals("Missing message: missing.key", PLAIN.serialize(bundle.render("missing.key", "")));
    }
    @Test
    void progressMessagesUseDefaultsWithoutReplacingCustomMessages() throws Exception {
        var custom = new YamlConfiguration();
        custom.options().pathSeparator('/');
        custom.loadFromString("messages:\n  done.done: 'Custom done message'\n");
        var defaults = new YamlConfiguration();
        defaults.options().pathSeparator('/');
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream);
            defaults.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        custom.setDefaults(defaults);
        var messages = new MessageBundle(custom);
        assertEquals("Generating geometry...", PLAIN.serialize(messages.render("info.progress", "Generating geometry...")));
        assertEquals("Custom done message", PLAIN.serialize(messages.render("done.done", "")));
    }
}
