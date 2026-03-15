package com.thaumicrecipeaspectpatcher.trap.config;

import com.thaumicrecipeaspectpatcher.trap.Tags;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.*;

/**
 * Manages the TRAP config file at config/trap.cfg.
 *
 * Config line format:
 *   modid:recipe_type_id
 *   modid:recipe_type_id:[inputSlot1,inputSlot2,...]
 *   modid:recipe_type_id:[inputSlot1,...]:[outputSlot1,...]
 *
 * Lines starting with '#' are comments. Blank lines are ignored.
 *
 * Example:
 *   minecraft:crafting
 *   thaumcraft:arcane_crafting:[0,1]
 *   thaumcraft:infusion:[]:[1]
 */
public class TRAPConfig {

    /**
     * Static instance populated during FMLPreInitializationEvent.
     * TRAPJeiPlugin reads from here so the main mod class never needs to
     * reference TRAPJeiPlugin directly (which would trigger early classloading
     * and crash if JEI is absent).
     */
    public static TRAPConfig INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    private static final String CONFIG_CATEGORY = "rules";
    private static final String CONFIG_KEY = "recipe_rules";
    private static final String[] DEFAULT_RULES = {
        "# Format: modid:recipe_type_id:[blacklist_input_slots]:[blacklist_output_slots]",
        "# The last two bracket parameters are optional.",
        "#",
        "# If the JEI category UID contains no colon (no modid prefix),",
        "# write the full UID directly without a modid:",
        "#   some.CategoryUid",
        "#   OrJustPlainId",
        "#",
        "# recipe_type_id must match a JEI category UID.",
        "# Slot indices are 0-based and comma-separated inside square brackets.",
        "#",
        "# Example - patch vanilla crafting recipes:",
        "#   minecraft:crafting",
        "# Example - patch Thaumcraft arcane crafting, skip output slot 1:",
        "#   thaumcraft:arcane_crafting:[]:[1]",
        "# Example - category whose UID has no colon:",
        "#   ic2.RecipeCategory",
        "# Example - patch all recipes in a custom JEI category:",
        "#   mymod:my_recipe_type"
    };

    private final List<RecipeRule> rules = new ArrayList<>();
    private File configDir;

    /** Returns the Forge mod-config directory used when this config was loaded. */
    public File getConfigDir() {
        return configDir;
    }

    public void load(File configDir) {
        this.configDir = configDir;
        File configFile = new File(configDir, Tags.MOD_ID + ".cfg");
        Configuration cfg = new Configuration(configFile);

        try {
            cfg.load();

            String[] rawRules = cfg.getStringList(
                CONFIG_KEY,
                CONFIG_CATEGORY,
                DEFAULT_RULES,
                "Recipe processing rules. Add one rule per line.\n"
                    + "Format: modid:recipe_type_id:[blacklist_inputs]:[blacklist_outputs]"
            );

            rules.clear();
            for (String raw : rawRules) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                RecipeRule rule = parseLine(line);
                if (rule != null) {
                    rules.add(rule);
                    LOGGER.info("Loaded rule: {}", rule);
                }
            }

        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }

        LOGGER.info("Loaded {} recipe rule(s) from config.", rules.size());
    }

    public List<RecipeRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * Parses a single config line into a RecipeRule.
     * Returns null and logs a warning on parse failure.
     *
     * Grammar:
     *   <uid>                                      (direct UID, no colon)
     *   <modid>:<recipe_type_id>                   (modid + type)
     *   <modid>:<recipe_type_id>:[<slots>]         (+ input blacklist)
     *   <modid>:<recipe_type_id>:[<slots>]:[<slots>] (+ output blacklist)
     *
     * If the line contains no colon before the first '[', the entire non-bracket
     * portion is treated as a direct JEI category UID (modId stored as "").
     *
     * where <slots> = comma-separated integers, may be empty.
     */
    private RecipeRule parseLine(String line) {
        try {
            // Split out optional bracket sections first to avoid ambiguity with `:` in UID.
            // Bracket sections are always `:[...]` so we split on `:['.
            String idPart;
            String inputPart = "";
            String outputPart = "";

            int bracketStart = line.indexOf(":[");
            if (bracketStart >= 0) {
                idPart = line.substring(0, bracketStart);
                String remainder = line.substring(bracketStart + 1); // starts with `[`

                int closeFirst = remainder.indexOf(']');
                if (closeFirst < 0) {
                    LOGGER.warn("Malformed rule (missing ']'): {}", line);
                    return null;
                }
                inputPart = remainder.substring(1, closeFirst).trim(); // inside first []

                // look for second bracket section `:[`
                String afterFirst = remainder.substring(closeFirst + 1);
                int secondBracket = afterFirst.indexOf(":[");
                if (secondBracket >= 0) {
                    String outputSection = afterFirst.substring(secondBracket + 1);
                    int closeSecond = outputSection.indexOf(']');
                    if (closeSecond >= 0) {
                        outputPart = outputSection.substring(1, closeSecond).trim();
                    }
                }
            } else {
                idPart = line;
            }

            String modId;
            String recipeTypeId;

            // If idPart has no colon, treat it as a raw/direct JEI category UID.
            int colonIdx = idPart.indexOf(':');
            if (colonIdx < 0) {
                modId = "";
                recipeTypeId = idPart.trim();
                if (recipeTypeId.isEmpty()) {
                    LOGGER.warn("Malformed rule (empty UID): {}", line);
                    return null;
                }
            } else {
                modId = idPart.substring(0, colonIdx).trim();
                recipeTypeId = idPart.substring(colonIdx + 1).trim();
                if (modId.isEmpty() || recipeTypeId.isEmpty()) {
                    LOGGER.warn("Malformed rule (empty modid or recipe_type_id): {}", line);
                    return null;
                }
            }

            Set<Integer> inputBlacklist = parseSlots(inputPart);
            Set<Integer> outputBlacklist = parseSlots(outputPart);

            return new RecipeRule(modId, recipeTypeId, inputBlacklist, outputBlacklist);

        } catch (Exception e) {
            LOGGER.warn("Failed to parse rule '{}': {}", line, e.getMessage());
            return null;
        }
    }

    /** Parses "1,2,3" or "" into a Set<Integer>. */
    private Set<Integer> parseSlots(String raw) {
        Set<Integer> result = new HashSet<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) {
                try {
                    result.add(Integer.parseInt(t));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid slot index '{}', ignoring.", t);
                }
            }
        }
        return result;
    }
}
