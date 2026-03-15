package com.thaumicrecipeaspectpatcher.trap.config;

import java.util.Collections;
import java.util.Set;

/**
 * Represents one line in the TRAP config file.
 * Format: modid:recipe_type_id:[inputBlacklist]:[outputBlacklist]
 */
public class RecipeRule {

    /** The original modid hint from the config (used to build candidate UIDs). */
    public final String modId;

    /** The recipe_type_id part of the config line. */
    public final String recipeTypeId;

    /**
     * Zero-based slot indices to skip when collecting input aspects.
     * Empty means process all input slots.
     */
    public final Set<Integer> blacklistedInputSlots;

    /**
     * Zero-based slot indices to skip when assigning output aspects.
     * Empty means process all output slots.
     */
    public final Set<Integer> blacklistedOutputSlots;

    public RecipeRule(String modId, String recipeTypeId,
                      Set<Integer> blacklistedInputSlots,
                      Set<Integer> blacklistedOutputSlots) {
        this.modId = modId;
        this.recipeTypeId = recipeTypeId;
        this.blacklistedInputSlots = Collections.unmodifiableSet(blacklistedInputSlots);
        this.blacklistedOutputSlots = Collections.unmodifiableSet(blacklistedOutputSlots);
    }

    /**
     * Returns a list of candidate JEI category UIDs to match against.
     *
     * When modId is empty the rule was written as a direct UID, so only the
     * bare recipeTypeId is tried. Otherwise several separator conventions are
     * attempted because mods register categories in various forms:
     *   "modid:type", "modid.type", "type", "modid:modid.type".
     */
    public String[] candidateUids() {
        if (modId.isEmpty()) {
            // Direct UID - user wrote the exact JEI category UID with no modid prefix.
            return new String[] { recipeTypeId };
        }
        return new String[] {
            modId + ":" + recipeTypeId,
            modId + "." + recipeTypeId,
            recipeTypeId,
            modId + ":" + modId + "." + recipeTypeId
        };
    }

    @Override
    public String toString() {
        return modId + ":" + recipeTypeId
                + " inputs_blacklist=" + blacklistedInputSlots
                + " outputs_blacklist=" + blacklistedOutputSlots;
    }
}
