package com.thaumicrecipeaspectpatcher.trap.config;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.Collections;
import java.util.Set;

/**
 * Represents one line in the TRAP config file.
 * Format: modid:recipe_type_id:[inputBlacklist]:[outputBlacklist]:[excludedItems]
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

    /**
     * Items to exclude from aspect calculation entirely (both input and output).
     * Each entry is either "modid:itemname@metadata" (exact match) or
     * "modid:itemname" (matches any metadata).
     */
    public final Set<String> excludedItems;

    public RecipeRule(String modId, String recipeTypeId,
                      Set<Integer> blacklistedInputSlots,
                      Set<Integer> blacklistedOutputSlots,
                      Set<String> excludedItems) {
        this.modId = modId;
        this.recipeTypeId = recipeTypeId;
        this.blacklistedInputSlots = Collections.unmodifiableSet(blacklistedInputSlots);
        this.blacklistedOutputSlots = Collections.unmodifiableSet(blacklistedOutputSlots);
        this.excludedItems = Collections.unmodifiableSet(excludedItems);
    }

    /**
     * Returns true if the given ItemStack matches any entry in {@link #excludedItems}.
     * Matching is done by registry name; if no {@code @metadata} suffix is present
     * in the filter entry, all metadata values are matched.
     */
    public boolean isItemExcluded(ItemStack stack) {
        if (excludedItems.isEmpty()) return false;
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation regName = stack.getItem().getRegistryName();
        if (regName == null) return false;
        String fullName = regName.toString();
        int meta = stack.getMetadata();
        return excludedItems.contains(fullName + "@" + meta)
                || excludedItems.contains(fullName);
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
                + " outputs_blacklist=" + blacklistedOutputSlots
                + " excluded_items=" + excludedItems;
    }
}
