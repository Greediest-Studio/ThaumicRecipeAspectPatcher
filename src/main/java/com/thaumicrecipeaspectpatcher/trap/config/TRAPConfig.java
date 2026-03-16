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
        "# Format: modid:recipe_type_id:[blacklist_input_slots]:[blacklist_output_slots]:[excluded_items]:[extra_aspects]",
        "# The last four bracket parameters are optional.",
        "#",
        "# If the JEI category UID contains no colon (no modid prefix),",
        "# write the full UID directly without a modid:",
        "#   some.CategoryUid",
        "#   OrJustPlainId",
        "#",
        "# recipe_type_id must match a JEI category UID.",
        "# Slot indices are 0-based and comma-separated inside square brackets.",
        "#",
        "# excluded_items: comma-separated list of items to exclude from aspect calculation",
        "# (applied to both inputs and outputs). Format: modid:item@metadata or modid:item",
        "# Omitting @metadata matches all metadata values.",
        "#",
        "# extra_aspects: comma-separated list of aspects unconditionally added to the sum.",
        "# Format: aspectname:amount (e.g. ignis:10,terra:5). Added before dividing by output count.",
        "#",
        "# Example - patch vanilla crafting recipes:",
        "#   minecraft:crafting",
        "# Example - patch Thaumcraft arcane crafting, skip output slot 1:",
        "#   thaumcraft:arcane_crafting:[]:[1]",
        "# Example - exclude specific items from aspect calculation:",
        "#   minecraft:crafting:[]:[]:[minecraft:stick,minecraft:planks@0]",
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
                    + "Format: modid:recipe_type_id:[blacklist_inputs]:[blacklist_outputs]:[excluded_items]"
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
     * Grammar (both separator styles are accepted):
     *   <uid>                                                    (no brackets)
     *   <uid>,[s1]                                               (comma-separated, up to 4 bracket sections)
     *   <uid>:[s1]:[s2]:[s3]:[s4]                               (colon-separated)
     *
     * Bracket sections in order: [inputSlots],[outputSlots],[excludedItems],[extraAspects]
     * Each section is optional (omit trailing sections or leave content empty).
     * The separator between UID and brackets, and between bracket sections, may be
     * either ',' or ':' — both are supported, and they may be mixed within one line.
     *
     * where <slots>   = comma-separated integers, may be empty.
     * where <items>   = comma-separated item identifiers (modid:item@metadata or modid:item).
     * where <aspects> = comma-separated aspect:amount pairs (e.g. ignis:10,terra:5).
     */
    private RecipeRule parseLine(String line) {
        try {
            String idPart;
            String inputPart = "";
            String outputPart = "";
            String itemsPart = "";
            String aspectsPart = "";

            int firstBracket = line.indexOf('[');
            if (firstBracket >= 0) {
                // Everything before the first '[', minus the immediate separator char (':' or ',').
                int sepIdx = firstBracket - 1;
                if (sepIdx >= 0 && (line.charAt(sepIdx) == ':' || line.charAt(sepIdx) == ',')) {
                    idPart = line.substring(0, sepIdx);
                } else {
                    idPart = line.substring(0, firstBracket);
                }

                // Scan bracket groups one by one.
                // Between groups the separator is exactly one ':' or ',' character.
                List<String> sections = new ArrayList<>();
                int pos = firstBracket;
                while (pos < line.length() && line.charAt(pos) == '[') {
                    int close = line.indexOf(']', pos + 1);
                    if (close < 0) {
                        LOGGER.warn("Malformed rule (missing ']'): {}", line);
                        return null;
                    }
                    sections.add(line.substring(pos + 1, close).trim());
                    pos = close + 1;
                    // Skip exactly one separator character before the next potential '['.
                    if (pos < line.length() && (line.charAt(pos) == ',' || line.charAt(pos) == ':')) {
                        pos++;
                    }
                }

                if (sections.size() > 0) inputPart   = sections.get(0);
                if (sections.size() > 1) outputPart  = sections.get(1);
                if (sections.size() > 2) itemsPart   = sections.get(2);
                if (sections.size() > 3) aspectsPart = sections.get(3);
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
            Set<String> excludedItems = parseItemList(itemsPart);
            Map<String, Integer> extraAspects = parseAspectMap(aspectsPart);

            return new RecipeRule(modId, recipeTypeId, inputBlacklist, outputBlacklist, excludedItems, extraAspects);

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

    /**
     * Parses a comma-separated item list into a Set of item identifier strings.
     * Each entry should be "modid:itemname@metadata" or "modid:itemname".
     * Entries are stored as-is (lowercased) for comparison against ItemStack registry names.
     */
    private Set<String> parseItemList(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String tok : raw.split(",")) {
            String t = tok.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * Parses a comma-separated aspect:amount list into a Map.
     * Each token must be "aspectname:integer" (e.g. "ignis:10").
     * Aspect names are stored lowercased.
     */
    private Map<String, Integer> parseAspectMap(String raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            int colon = t.lastIndexOf(':');
            if (colon <= 0 || colon == t.length() - 1) {
                LOGGER.warn("Invalid extra aspect entry '{}', expected 'aspectname:amount', ignoring.", t);
                continue;
            }
            String aspectName = t.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String amountStr = t.substring(colon + 1).trim();
            try {
                int amount = Integer.parseInt(amountStr);
                if (amount > 0) {
                    result.merge(aspectName, amount, Integer::sum);
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid aspect amount '{}' in '{}', ignoring.", amountStr, t);
            }
        }
        return result;
    }
}
