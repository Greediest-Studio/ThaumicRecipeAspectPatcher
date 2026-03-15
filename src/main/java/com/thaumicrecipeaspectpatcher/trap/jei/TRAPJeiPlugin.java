package com.thaumicrecipeaspectpatcher.trap.jei;

import com.thaumicrecipeaspectpatcher.trap.Tags;
import com.thaumicrecipeaspectpatcher.trap.config.RecipeRule;
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
import thaumcraft.api.aspects.AspectEventProxy;
import thaumcraft.api.aspects.AspectList;

import java.util.List;

/**
 * JEI plugin entry point for ThaumicRecipeAspectPatcher.
 *
 * When JEI finishes initializing ({@code onRuntimeAvailable}), this class
 * iterates every configured recipe rule, finds the matching JEI category,
 * and for each recipe wrapper:
 *   1. Collects all non-blacklisted input ItemStacks.
 *   2. Sums their Thaumcraft aspects via ThaumcraftApi.internalMethods.
 *   3. Registers the summed aspects on every non-blacklisted output ItemStack
 *      via AspectEventProxy.registerComplexObjectTag.
 *
 * AspectEventProxy writes directly into CommonInternals.objectTags (a
 * ConcurrentHashMap), so calling it at runtime is safe.
 */
@JEIPlugin
@SideOnly(Side.CLIENT)
public class TRAPJeiPlugin implements IModPlugin {

    private static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        TRAPConfig config = TRAPConfig.INSTANCE;
        if (config == null) {
            LOGGER.warn("TRAPJeiPlugin: TRAPConfig.INSTANCE is null - skipping aspect patching.");
            return;
        }

        List<RecipeRule> rules = config.getRules();
        if (rules.isEmpty()) {
            LOGGER.info("No recipe rules configured - nothing to patch.");
            return;
        }

        // AspectEventProxy has a public no-arg constructor; its methods write to
        // CommonInternals.objectTags (ConcurrentHashMap) which is always accessible.
        AspectEventProxy proxy = new AspectEventProxy();
        IRecipeRegistry registry = runtime.getRecipeRegistry();
        int totalPatched = 0;

        for (RecipeRule rule : rules) {
            IRecipeCategory<?> category = findCategory(registry, rule);
            if (category == null) {
                LOGGER.warn("No JEI category found for rule '{}:{}' - tried UIDs: {}",
                        rule.modId, rule.recipeTypeId,
                        String.join(", ", rule.candidateUids()));
                continue;
            }

            int patched = processCategory(registry, proxy, category, rule);
            LOGGER.info("Rule '{}:{}' - patched {} output(s) in category '{}'.",
                    rule.modId, rule.recipeTypeId, patched, category.getUid());
            totalPatched += patched;
        }

        LOGGER.info("TRAP aspect patching complete - total outputs patched: {}.", totalPatched);
    }

    // -------------------------------------------------------------------------

    /** Tries each candidate UID until a matching JEI category is found. */
    @SuppressWarnings("unchecked")
    private IRecipeCategory<?> findCategory(IRecipeRegistry registry, RecipeRule rule) {
        for (String uid : rule.candidateUids()) {
            IRecipeCategory<?> cat = registry.getRecipeCategory(uid);
            if (cat != null) {
                return cat;
            }
        }
        return null;
    }

    /** Processes all recipes in a category. Returns count of patched outputs. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int processCategory(IRecipeRegistry registry,
                                AspectEventProxy proxy,
                                IRecipeCategory category,
                                RecipeRule rule) {
        List<IRecipeWrapper> wrappers = registry.getRecipeWrappers(category);
        int count = 0;
        for (IRecipeWrapper wrapper : wrappers) {
            count += processWrapper(proxy, wrapper, rule);
        }
        return count;
    }

    /**
     * Sums input aspects and assigns them to output ItemStacks for one recipe.
     *
     * @return number of output ItemStacks patched.
     */
    private int processWrapper(AspectEventProxy proxy,
                               IRecipeWrapper wrapper,
                               RecipeRule rule) {
        // Use JEI's own Ingredients class - at onRuntimeAvailable time
        // Internal.getIngredientRegistry() is fully available.
        Ingredients ingredients = new Ingredients();
        try {
            wrapper.getIngredients(ingredients);
        } catch (Exception e) {
            LOGGER.debug("getIngredients() threw for {}: {}",
                    wrapper.getClass().getName(), e.getMessage());
            return 0;
        }

        // Sum aspects from input slots (skip blacklisted slots).
        List<List<ItemStack>> inputLists = ingredients.getInputs(VanillaTypes.ITEM);
        AspectList summed = new AspectList();
        for (int slot = 0; slot < inputLists.size(); slot++) {
            if (rule.blacklistedInputSlots.contains(slot)) continue;
            List<ItemStack> slotStacks = inputLists.get(slot);
            if (slotStacks == null || slotStacks.isEmpty()) continue;
            // All stacks in the same slot are alternates (subtypes); pick the first representative.
            ItemStack rep = slotStacks.get(0);
            if (rep == null || rep.isEmpty()) continue;
            AspectList itemAspects = ThaumcraftApi.internalMethods.getObjectAspects(rep);
            if (itemAspects == null) continue;
            // getObjectAspects returns aspects for a single item regardless of stack size,
            // so multiply by the count actually required by this recipe slot.
            // NOTE: use add() not merge()! merge(Aspect,int) takes MAX of old/new values,
            // whereas add(Aspect,int) accumulates (iadd). Using merge would cause all
            // identical-aspect inputs to count only once instead of being summed.
            int count = Math.max(1, rep.getCount());
            for (Aspect aspect : itemAspects.getAspects()) {
                summed.add(aspect, itemAspects.getAmount(aspect) * count);
            }
        }

        if (summed.size() == 0) {
            return 0;
        }

        // Assign aspects to output slots (skip blacklisted slots).
        // Thaumcraft stores aspects as a per-item property, and registerComplexObjectTag
        // sets the value for ONE instance of that item. Divide the total input sum by the
        // recipe output count so that value is proportionally correct:
        //   64 cobble (Terra×64) → 5 giant cobble: each giant = Terra×12 (64/5)
        //   64 cobble (Terra×64) → 1 giant cobble:  each giant = Terra×64 (64/1)
        // Integer division is used; minimum per-aspect value is 1.
        List<List<ItemStack>> outputLists = ingredients.getOutputs(VanillaTypes.ITEM);
        int patched = 0;
        for (int slot = 0; slot < outputLists.size(); slot++) {
            if (rule.blacklistedOutputSlots.contains(slot)) continue;
            List<ItemStack> slotStacks = outputLists.get(slot);
            if (slotStacks == null) continue;
            for (ItemStack output : slotStacks) {
                if (output == null || output.isEmpty()) continue;
                int outCount = Math.max(1, output.getCount());
                AspectList perItem = scaleAspects(summed, outCount);
                proxy.registerComplexObjectTag(output.copy(), perItem);
                patched++;
            }
        }
        return patched;
    }

    /**
     * Returns a new AspectList with every value divided by {@code divisor}.
     * Integer division; each aspect's value is at least 1.
     */
    private static AspectList scaleAspects(AspectList src, int divisor) {
        if (divisor <= 1) return src.copy();
        AspectList result = new AspectList();
        for (Aspect aspect : src.getAspects()) {
            int amount = Math.max(1, src.getAmount(aspect) / divisor);
            result.merge(aspect, amount);
        }
        return result;
    }
}
