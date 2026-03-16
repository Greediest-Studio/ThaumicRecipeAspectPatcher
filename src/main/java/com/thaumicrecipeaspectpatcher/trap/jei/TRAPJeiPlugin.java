package com.thaumicrecipeaspectpatcher.trap.jei;

import com.thaumicrecipeaspectpatcher.trap.Tags;
import com.thaumicrecipeaspectpatcher.trap.config.RecipeRule;
import com.thaumicrecipeaspectpatcher.trap.config.TRAPCache;
import com.thaumicrecipeaspectpatcher.trap.config.TRAPConfig;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.ingredients.Ingredients;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JEI plugin entry point for ThaumicRecipeAspectPatcher (CLIENT ONLY).
 *
 * Runs only when JEI is present.  On {@code onRuntimeAvailable}:
 *   1. Check whether {@code config/trap_cache.json} is still valid
 *      (its stored config-hash matches the current {@code trap.cfg}).
 *   2. If valid  → load the cache and apply it to the client's objectTags.
 *   3. If invalid/missing → iterate all configured JEI recipe categories,
 *      compute per-output AspectLists, write a new cache, then apply it.
 *
 * The server side never runs JEI; instead it reads the same cache file during
 * FMLServerStartingEvent (see ThaumicRecipeAspectPatcher) and applies it to
 * the server's objectTags.
 */
@JEIPlugin
@SideOnly(Side.CLIENT)
public class TRAPJeiPlugin implements IModPlugin {

    private static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        TRAPConfig config = TRAPConfig.INSTANCE;
        if (config == null) {
            LOGGER.warn("TRAP: TRAPConfig.INSTANCE is null - skipping aspect patching.");
            return;
        }

        File configDir = config.getConfigDir();

        // --- Try loading an existing valid cache first ---
        TRAPCache existing = TRAPCache.load(configDir);
        if (existing != null && existing.isValid(configDir)) {
            LOGGER.info("TRAP: Valid cache found ({} entries). Applying to client.", existing.entries.size());
            int applied = existing.apply();
            LOGGER.info("TRAP: Client applied {} aspect override(s) from cache.", applied);
            return;
        }

        // --- Cache missing or outdated: recompute from JEI ---
        LOGGER.info("TRAP: Cache is missing or outdated - recomputing from JEI recipes...");

        List<RecipeRule> rules = config.getRules();
        TRAPCache newCache = new TRAPCache();
        newCache.configHash = TRAPCache.hashConfigFile(configDir);
        newCache.formatVersion = TRAPCache.CURRENT_FORMAT_VERSION;
        newCache.entries = new ArrayList<>();

        if (rules.isEmpty()) {
            LOGGER.info("TRAP: No rules configured - saving empty cache.");
            newCache.save(configDir);
            return;
        }

        IRecipeRegistry registry = runtime.getRecipeRegistry();

        // ---- Phase 1: compute aspects using only existing game data ----
        // Wrappers whose inputs contained items with no aspects are queued for
        // a second pass once the phase-1 outputs have been collected.
        List<Object[]> retryQueue = new ArrayList<>(); // { IRecipeWrapper, RecipeRule, Set<String> missingKeys }
        for (RecipeRule rule : rules) {
            IRecipeCategory<?> category = findCategory(registry, rule);
            if (category == null) {
                LOGGER.warn("TRAP: No JEI category found for rule '{}:{}' - tried UIDs: {}",
                        rule.modId, rule.recipeTypeId,
                        String.join(", ", rule.candidateUids()));
                continue;
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<IRecipeWrapper> wrappers = ((IRecipeRegistry) registry).getRecipeWrappers((IRecipeCategory) category);
            int count = 0;
            for (IRecipeWrapper wrapper : wrappers) {
                WrapperResult wr = processWrapper(wrapper, rule, Collections.emptyMap());
                newCache.entries.addAll(wr.entries);
                count += wr.entries.size();
                if (!wr.missingInputKeys.isEmpty()) {
                    retryQueue.add(new Object[]{wrapper, rule, wr.missingInputKeys});
                }
            }
            LOGGER.info("TRAP: Rule '{}:{}' (phase 1) \u2192 {} cache entries in category '{}'.",
                    rule.modId, rule.recipeTypeId, count, category.getUid());
        }

        // ---- Phase 2: re-evaluate wrappers that had missing-aspect inputs ----
        // Build a supplementary map from phase-1 outputs so subsequent lookups
        // can resolve items that were not in the game's objectTags yet.
        if (!retryQueue.isEmpty()) {
            Map<String, AspectList> supplementary = new HashMap<>();
            for (TRAPCache.Entry e : newCache.entries) {
                AspectList al = e.toAspectList();
                if (al.size() > 0) {
                    supplementary.putIfAbsent(e.registryName + "@" + e.meta, al);
                }
            }

            // Index existing entries so phase-2 results can replace them.
            Map<String, TRAPCache.Entry> entriesMap = new LinkedHashMap<>();
            for (TRAPCache.Entry e : newCache.entries) {
                entriesMap.put(entryKey(e), e);
            }

            int retriedCount = 0;
            for (Object[] item : retryQueue) {
                IRecipeWrapper wrapper = (IRecipeWrapper) item[0];
                RecipeRule rule = (RecipeRule) item[1];
                @SuppressWarnings("unchecked")
                Set<String> missingKeys = (Set<String>) item[2];

                // Only worth retrying if at least one previously-missing input
                // has now been assigned aspects in supplementary.
                boolean anyResolved = false;
                for (String key : missingKeys) {
                    if (supplementary.containsKey(key)) { anyResolved = true; break; }
                }
                if (!anyResolved) continue;

                WrapperResult retried = processWrapper(wrapper, rule, supplementary);
                if (retried.entries.isEmpty()) continue;

                for (TRAPCache.Entry e : retried.entries) {
                    entriesMap.put(entryKey(e), e);
                }
                retriedCount++;
            }

            if (retriedCount > 0) {
                LOGGER.info("TRAP: Phase 2 re-evaluated {} wrapper(s) with resolved inputs. Total entries: {}.",
                        retriedCount, entriesMap.size());
                newCache.entries = new ArrayList<>(entriesMap.values());
            }
        }

        LOGGER.info("TRAP: Computed {} total aspect entries. Saving cache...", newCache.entries.size());
        newCache.save(configDir);

        int applied = newCache.apply();
        LOGGER.info("TRAP: Client applied {} aspect override(s) from newly computed cache.", applied);
    }

    // -------------------------------------------------------------------------

    /** Tries each candidate UID until a matching JEI category is found. */
    @SuppressWarnings("unchecked")
    private IRecipeCategory<?> findCategory(IRecipeRegistry registry, RecipeRule rule) {
        for (String uid : rule.candidateUids()) {
            IRecipeCategory<?> cat = registry.getRecipeCategory(uid);
            if (cat != null) return cat;
        }
        return null;
    }

    // -------------------------------------------------------------------------

    /**
     * Result of processing one recipe wrapper.
     * {@code missingInputKeys} contains "registryName@meta" keys for every
     * non-excluded, non-empty input slot that had no aspect data from any source.
     */
    private static class WrapperResult {
        final List<TRAPCache.Entry> entries;
        final Set<String> missingInputKeys;
        WrapperResult(List<TRAPCache.Entry> entries, Set<String> missingInputKeys) {
            this.entries = entries;
            this.missingInputKeys = missingInputKeys;
        }
    }

    /**
     * Returns a unique string key for a cache entry, used to detect duplicate
     * output items across wrappers so phase-2 results can replace phase-1 ones.
     */
    private static String entryKey(TRAPCache.Entry e) {
        return e.registryName + "@" + e.meta + (e.nbt != null ? "#" + e.nbt : "");
    }

    /**
     * Look up aspects for {@code stack}.
     * Checks {@code supplementary} first (phase-2 computed data), then falls
     * back to Thaumcraft's live objectTags.
     */
    private static AspectList lookupAspects(ItemStack stack, Map<String, AspectList> supplementary) {
        if (!supplementary.isEmpty()) {
            ResourceLocation rn = stack.getItem().getRegistryName();
            if (rn != null) {
                AspectList sup = supplementary.get(rn.toString() + "@" + stack.getMetadata());
                if (sup != null && sup.size() > 0) return sup;
            }
        }
        return ThaumcraftApi.internalMethods.getObjectAspects(stack);
    }

    /**
     * Sums input aspects and builds one {@link TRAPCache.Entry} per output
     * ItemStack for a single recipe wrapper.
     *
     * @param supplementary aspects computed by phase 1, used to resolve inputs
     *                       that had no data in the live game objectTags yet.
     *                       Pass an empty map during phase 1.
     */
    private WrapperResult processWrapper(IRecipeWrapper wrapper, RecipeRule rule,
                                         Map<String, AspectList> supplementary) {
        Ingredients ingredients = new Ingredients();
        try {
            wrapper.getIngredients(ingredients);
        } catch (Exception e) {
            LOGGER.debug("TRAP: getIngredients() threw for {}: {}",
                    wrapper.getClass().getName(), e.getMessage());
            return new WrapperResult(Collections.emptyList(), Collections.emptySet());
        }

        // Sum aspects from all non-blacklisted input slots.
        // add(aspect, amount) accumulates correctly; merge() would take MAX instead.
        List<List<ItemStack>> inputLists = ingredients.getInputs(VanillaTypes.ITEM);
        AspectList summed = new AspectList();
        Set<String> missingInputKeys = new HashSet<>();
        for (int slot = 0; slot < inputLists.size(); slot++) {
            if (rule.blacklistedInputSlots.contains(slot)) continue;
            List<ItemStack> slotStacks = inputLists.get(slot);
            if (slotStacks == null || slotStacks.isEmpty()) continue;
            // Multiple stacks in one slot = alternates; use the first as representative.
            ItemStack rep = slotStacks.get(0);
            if (rep == null || rep.isEmpty()) continue;
            if (rule.isItemExcluded(rep)) continue;
            AspectList itemAspects = lookupAspects(rep, supplementary);
            if (itemAspects == null || itemAspects.size() == 0) {
                // Record this item as having no aspects so the caller can decide
                // whether a phase-2 retry would help.
                ResourceLocation rn = rep.getItem().getRegistryName();
                if (rn != null) missingInputKeys.add(rn.toString() + "@" + rep.getMetadata());
                continue;
            }
            // getObjectAspects returns per-item values; scale by actual slot count.
            int count = Math.max(1, rep.getCount());
            for (Aspect aspect : itemAspects.getAspects()) {
                summed.add(aspect, itemAspects.getAmount(aspect) * count);
            }
        }

        // Add any extra aspects declared in the rule.
        for (Map.Entry<String, Integer> entry : rule.extraAspects.entrySet()) {
            Aspect aspect = Aspect.getAspect(entry.getKey());
            if (aspect != null) {
                summed.add(aspect, entry.getValue());
            } else {
                LOGGER.warn("TRAP: Unknown aspect tag '{}' in extra_aspects for rule '{}:{}', ignoring.",
                        entry.getKey(), rule.modId, rule.recipeTypeId);
            }
        }

        if (summed.size() == 0) {
            return new WrapperResult(Collections.emptyList(), missingInputKeys);
        }

        // Build one cache entry per output stack, dividing the summed aspects by
        // the output count so that each item gets a proportional per-item value.
        //   64 cobble → 5 giant cobble: each giant = floor(Terra×64 / 5) = Terra×12
        //   64 cobble → 1 giant cobble: each giant = Terra×64
        List<List<ItemStack>> outputLists = ingredients.getOutputs(VanillaTypes.ITEM);
        List<TRAPCache.Entry> result = new ArrayList<>();
        for (int slot = 0; slot < outputLists.size(); slot++) {
            if (rule.blacklistedOutputSlots.contains(slot)) continue;
            List<ItemStack> slotStacks = outputLists.get(slot);
            if (slotStacks == null) continue;
            for (ItemStack output : slotStacks) {
                if (output == null || output.isEmpty()) continue;
                if (rule.isItemExcluded(output)) continue;
                int outCount = Math.max(1, output.getCount());
                AspectList perItem = scaleAspects(summed, outCount);
                result.add(TRAPCache.Entry.of(output, perItem));
            }
        }
        return new WrapperResult(result, missingInputKeys);
    }

    /**
     * Returns a copy of {@code src} with every aspect value divided by
     * {@code divisor} (integer division, minimum 1 per aspect).
     */
    private static AspectList scaleAspects(AspectList src, int divisor) {
        if (divisor <= 1) return src.copy();
        AspectList result = new AspectList();
        for (Aspect aspect : src.getAspects()) {
            int amount = Math.max(1, src.getAmount(aspect) / divisor);
            result.add(aspect, amount);
        }
        return result;
    }
}
