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
import java.util.List;

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
        newCache.entries = new ArrayList<>();

        if (rules.isEmpty()) {
            LOGGER.info("TRAP: No rules configured - saving empty cache.");
            newCache.save(configDir);
            return;
        }

        IRecipeRegistry registry = runtime.getRecipeRegistry();
        for (RecipeRule rule : rules) {
            IRecipeCategory<?> category = findCategory(registry, rule);
            if (category == null) {
                LOGGER.warn("TRAP: No JEI category found for rule '{}:{}' - tried UIDs: {}",
                        rule.modId, rule.recipeTypeId,
                        String.join(", ", rule.candidateUids()));
                continue;
            }
            List<TRAPCache.Entry> entries = processCategory(registry, category, rule);
            LOGGER.info("TRAP: Rule '{}:{}' → {} cache entries in category '{}'.",
                    rule.modId, rule.recipeTypeId, entries.size(), category.getUid());
            newCache.entries.addAll(entries);
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

    /** Processes all wrappers in a category and collects cache entries. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<TRAPCache.Entry> processCategory(IRecipeRegistry registry,
                                                  IRecipeCategory category,
                                                  RecipeRule rule) {
        List<IRecipeWrapper> wrappers = registry.getRecipeWrappers(category);
        List<TRAPCache.Entry> result = new ArrayList<>();
        for (IRecipeWrapper wrapper : wrappers) {
            result.addAll(processWrapper(wrapper, rule));
        }
        return result;
    }

    /**
     * Sums input aspects and builds one {@link TRAPCache.Entry} per output
     * ItemStack for a single recipe wrapper.
     */
    private List<TRAPCache.Entry> processWrapper(IRecipeWrapper wrapper, RecipeRule rule) {
        Ingredients ingredients = new Ingredients();
        try {
            wrapper.getIngredients(ingredients);
        } catch (Exception e) {
            LOGGER.debug("TRAP: getIngredients() threw for {}: {}",
                    wrapper.getClass().getName(), e.getMessage());
            return Collections.emptyList();
        }

        // Sum aspects from all non-blacklisted input slots.
        // add(aspect, amount) accumulates correctly; merge() would take MAX instead.
        List<List<ItemStack>> inputLists = ingredients.getInputs(VanillaTypes.ITEM);
        AspectList summed = new AspectList();
        for (int slot = 0; slot < inputLists.size(); slot++) {
            if (rule.blacklistedInputSlots.contains(slot)) continue;
            List<ItemStack> slotStacks = inputLists.get(slot);
            if (slotStacks == null || slotStacks.isEmpty()) continue;
            // Multiple stacks in one slot = alternates; use the first as representative.
            ItemStack rep = slotStacks.get(0);
            if (rep == null || rep.isEmpty()) continue;
            AspectList itemAspects = ThaumcraftApi.internalMethods.getObjectAspects(rep);
            if (itemAspects == null) continue;
            // getObjectAspects returns per-item values; scale by actual slot count.
            int count = Math.max(1, rep.getCount());
            for (Aspect aspect : itemAspects.getAspects()) {
                summed.add(aspect, itemAspects.getAmount(aspect) * count);
            }
        }

        if (summed.size() == 0) return Collections.emptyList();

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
                int outCount = Math.max(1, output.getCount());
                AspectList perItem = scaleAspects(summed, outCount);
                result.add(TRAPCache.Entry.of(output, perItem));
            }
        }
        return result;
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
