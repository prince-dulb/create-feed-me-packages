package dev.scathiard.feedmepackages.growth;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

/** Visible to the native smithing recipe system; ownership is committed only by GrowthService. */
public final class PendantSmithingRecipe extends SmithingTransformRecipe {
    private final Ingredient template, base, addition;
    private final ItemStack result;
    private final int targetLevel;
    public PendantSmithingRecipe(Ingredient template, Ingredient base, Ingredient addition, ItemStack result, int targetLevel) {
        super(template, base, addition, result.copy());
        if ((targetLevel != 0 && (targetLevel < 2 || targetLevel > 5)) || result.getCount() != 1
                || !(result.getItem() instanceof PendantItem pendant) || (targetLevel == 0 && !pendant.personal()))
            throw new IllegalArgumentException("Invalid pendant smithing definition");
        this.template = template; this.base = base; this.addition = addition; this.result = result.copy(); this.targetLevel = targetLevel;
    }
    public int targetLevel() { return targetLevel; }
    public Ingredient templateIngredient() { return template; }
    public Ingredient baseIngredient() { return base; }
    public Ingredient additionIngredient() { return addition; }
    @Override public boolean matches(SmithingRecipeInput input, Level level) {
        return input.base().getCount() == 1 && super.matches(input, level);
    }
    @Override public ItemStack assemble(SmithingRecipeInput input, HolderLookup.Provider registries) {
        // A generic/automated caller must not turn a loaded cache into an uncommitted result.
        return ItemStack.EMPTY;
    }
    public ItemStack display(SmithingRecipeInput input) { return input.base().transmuteCopy(result.getItem(), 1); }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) {
        ItemStack view = result.copy(); view.set(FmpRegistries.PREVIEW.get(), true); return view;
    }
    @Override public RecipeSerializer<?> getSerializer() { return FmpRegistries.PENDANT_SMITHING.get(); }
    public static final class Serializer implements RecipeSerializer<PendantSmithingRecipe> {
        private static final MapCodec<PendantSmithingRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ingredient.CODEC.fieldOf("template").forGetter(r -> r.template),
                Ingredient.CODEC.fieldOf("base").forGetter(r -> r.base),
                Ingredient.CODEC.fieldOf("addition").forGetter(r -> r.addition),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(r -> r.result),
                com.mojang.serialization.Codec.INT.fieldOf("target_level").forGetter(r -> r.targetLevel)).apply(i, PendantSmithingRecipe::new));
        private static final StreamCodec<RegistryFriendlyByteBuf, PendantSmithingRecipe> STREAM = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, r -> r.template, Ingredient.CONTENTS_STREAM_CODEC, r -> r.base,
                Ingredient.CONTENTS_STREAM_CODEC, r -> r.addition, ItemStack.STREAM_CODEC, r -> r.result,
                ByteBufCodecs.VAR_INT, r -> r.targetLevel, PendantSmithingRecipe::new);
        @Override public MapCodec<PendantSmithingRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, PendantSmithingRecipe> streamCodec() { return STREAM; }
    }
}
