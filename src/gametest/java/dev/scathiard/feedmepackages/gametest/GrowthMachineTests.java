package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.fluids.transfer.FillingRecipe;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.kinetics.deployer.DeployerApplicationRecipe;
import com.simibubi.create.content.kinetics.deployer.DeployerBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingRecipe;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.createmod.catnip.math.Pointing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.*;

/** Real placed Create machines, native world ticks and item/fluid capabilities; no forced recipe results. */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GrowthMachineTests {
    private static final BlockPos PRESS = new BlockPos(2, 3, 4), SAW = new BlockPos(7, 1, 4), SPOUT = new BlockPos(12, 3, 4), DEPLOYER = new BlockPos(17, 3, 4);

    @GameTest(template = "assembly", timeoutTicks = 3000)
    public static void physicalMachinesAdvanceAllLinksAndChargeRealInputs(GameTestHelper helper) {
        place(helper, PRESS, AllBlocks.MECHANICAL_PRESS.getDefaultState()); power(helper, PRESS);
        place(helper, SAW, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(SawBlock.FACING, Direction.UP)); power(helper, SAW);
        place(helper, SPOUT, AllBlocks.SPOUT.getDefaultState());
        place(helper, DEPLOYER, AllBlocks.DEPLOYER.getDefaultState().setValue(DeployerBlock.FACING, Direction.DOWN)); power(helper, DEPLOYER);
        for (var pos : List.of(PRESS, SPOUT, DEPLOYER)) place(helper, pos.below(2), AllBlocks.DEPOT.getDefaultState());
        var run = new AssemblyRun(helper); helper.onEachTick(run::tick);
    }

    private static final class AssemblyRun {
        final GameTestHelper helper; BlockPos sawOutput;
        final int[] targets = {2, 3, 4, 5, 0};
        int targetIndex, stepIndex, attempts, waiting; boolean inserted, missingChecked;
        ItemStack current = ItemStack.EMPTY; ProcessingRecipe<?, ?> step; IItemHandler input, output, hand; IFluidHandler tank;
        AssemblyRun(GameTestHelper helper) { this.helper = helper; }
        void tick() {
            if (sawOutput == null) {
                if (helper.getTick() < 20) return;
                for (var pos : List.of(PRESS, SAW, DEPLOYER)) helper.assertTrue(((KineticBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(pos))).getSpeed() != 0, "Native motor did not power " + pos);
                var saw = (SawBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(SAW)); var movement = saw.getItemMovementVec();
                sawOutput = SAW.offset((int)movement.x, 0, (int)movement.z); place(helper, sawOutput, AllBlocks.DEPOT.getDefaultState()); return;
            }
            if (targetIndex == targets.length) { helper.succeed(); return; }
            int target = targets[targetIndex];
            var sequence = (SequencedAssemblyRecipe)helper.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,
                    "sequenced_assembly/" + (target == 0 ? "private_link" : "upgrade_link_" + target))).orElseThrow().value();
            if (!inserted) {
                if (current.isEmpty()) current = target == 0 ? new ItemStack(Items.ENDER_PEARL) : FmpRegistries.LINK_FRAMES.get(target - 2).toStack();
                step = sequence.getSequence().get(stepIndex).getRecipe();
                BlockPos station = step instanceof PressingRecipe ? PRESS.below(2) : step instanceof FillingRecipe ? SPOUT.below(2) : step instanceof DeployerApplicationRecipe ? DEPLOYER.below(2) : SAW;
                input = items(helper, station); output = station.equals(SAW) ? items(helper, sawOutput) : input;
                helper.assertTrue(input.insertItem(0, current.copy(), false).isEmpty(), "Native machine rejected its sequence input " + target + "/" + stepIndex);
                if (step instanceof DeployerApplicationRecipe) { hand = items(helper, DEPLOYER); helper.assertTrue(hand.getStackInSlot(0).isEmpty(), "Prior deployer input was not removed"); }
                if (step instanceof FillingRecipe) {
                    tank = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(SPOUT), Direction.UP);
                    var required = step.getFluidIngredients().getFirst(); tank.drain(1000, IFluidHandler.FluidAction.EXECUTE);
                    helper.assertTrue(tank.fill(required.getFluids()[0].copyWithAmount(required.amount() - 1), IFluidHandler.FluidAction.EXECUTE) == required.amount() - 1, "Spout fixture failed to insert its insufficient amount");
                }
                inserted = true; missingChecked = !(step instanceof DeployerApplicationRecipe || step instanceof FillingRecipe); waiting = 0; return;
            }
            waiting++;
            helper.assertTrue(waiting < 160, "Native processing stalled at link " + target + " step " + stepIndex);
            if (!missingChecked) {
                if (waiting < 25) return;
                helper.assertTrue(ItemStack.matches(input.getStackInSlot(0), current), "Missing reagent still processed the input");
                if (step instanceof DeployerApplicationRecipe) helper.assertTrue(hand.insertItem(0, step.getIngredients().get(1).getItems()[0].copyWithCount(2), false).isEmpty(), "Deployer rejected its real reagent");
                else {
                    var required = step.getFluidIngredients().getFirst(); helper.assertTrue(tank.getFluidInTank(0).getAmount() == required.amount() - 1, "Insufficient fluid was charged");
                    tank.fill(required.getFluids()[0].copyWithAmount(required.amount() + 1), IFluidHandler.FluidAction.EXECUTE);
                }
                missingChecked = true; return;
            }
            for (int slot = 0; slot < output.getSlots(); slot++) {
                var candidate = output.getStackInSlot(slot);
                if (candidate.isEmpty() || ItemStack.matches(candidate, current)) continue;
                var result = output.extractItem(slot, 1, false); helper.assertTrue(!result.isEmpty(), "Processed native output was not retrievable");
                helper.assertTrue(total(input) == 0 && (input == output || total(output) == 0), "Machine retained an extra input/output copy");
                if (step instanceof DeployerApplicationRecipe) helper.assertTrue(hand.extractItem(0, 64, false).getCount() == 1 && total(hand) == 0, "Deployer did not consume exactly one of two supplied reagents");
                if (step instanceof FillingRecipe) helper.assertTrue(tank.getFluidInTank(0).getAmount() == step.getFluidIngredients().getFirst().amount(), "Spout did not consume exactly one recipe's fluid");
                current = result; stepIndex++; inserted = false;
                if (stepIndex < sequence.getSequence().size()) {
                    helper.assertTrue(current.has(AllDataComponents.SEQUENCED_ASSEMBLY) && current.get(AllDataComponents.SEQUENCED_ASSEMBLY).step() == stepIndex, "Native machine skipped the transitional sequence step");
                } else {
                    helper.assertTrue(!current.has(FmpRegistries.IDENTITY.get()) && !current.has(FmpRegistries.OWNER.get()), "Random manufacturing acquired cache authority");
                    var wanted = target == 0 ? FmpRegistries.PRIVATE_LINK : FmpRegistries.upgradeLink(target);
                    if (current.is(wanted.get())) { FeedMePackages.LOGGER.info("FMP_PHYSICAL_SEQUENCE_PASSED link={} attempts={}", target, attempts + 1); targetIndex++; attempts = 0; }
                    else helper.assertTrue(++attempts < 12, "No actual link produced in twelve native attempts");
                    stepIndex = 0; current = ItemStack.EMPTY;
                }
                return;
            }
        }
    }

    @GameTest(template = "assembly", timeoutTicks = 400)
    public static void levelFourFrameIsMadeByActualMechanicalCrafters(GameTestHelper helper) { mechanicalFrame(helper, 4); }
    @GameTest(template = "assembly", timeoutTicks = 400)
    public static void levelFiveFrameIsMadeByActualMechanicalCrafters(GameTestHelper helper) { mechanicalFrame(helper, 5); }
    private static void mechanicalFrame(GameTestHelper helper, int level) {
        var recipe = (MechanicalCraftingRecipe)helper.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "mechanical_crafting/link_frame_" + level)).orElseThrow().value();
        var start = new BlockPos(11, 7, 6); var state = AllBlocks.MECHANICAL_CRAFTER.getDefaultState().setValue(MechanicalCrafterBlock.HORIZONTAL_FACING, Direction.NORTH).setValue(MechanicalCrafterBlock.POINTING, Pointing.RIGHT);
        var right = MechanicalCrafterBlock.getTargetDirection(state); var crafters = new ArrayList<MechanicalCrafterBlockEntity>();
        for (int row = 0; row < recipe.getHeight(); row++) for (int col = 0; col < recipe.getWidth(); col++) {
            var pos = start.relative(right, col).below(row); place(helper, pos, col == recipe.getWidth() - 1 ? state.setValue(MechanicalCrafterBlock.POINTING, Pointing.DOWN) : state);
            crafters.add((MechanicalCrafterBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
        }
        // Crafters accept side gear drive, not a shaft directly into their rear face.
        var gear = start.relative(right.getOpposite());
        place(helper, gear, AllBlocks.COGWHEEL.getDefaultState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z));
        power(helper, gear);
        helper.runAfterDelay(25, () -> {
            for (int index = 0; index < crafters.size(); index++) {
                var crafter = crafters.get(index); helper.assertTrue(crafter.getSpeed() != 0, "Crafter grid was not powered through its native gears at cell " + index);
                var ingredient = recipe.getIngredients().get(index); if (!ingredient.isEmpty()) helper.assertTrue(crafter.getInventory().insertItem(0, ingredient.getItems()[0].copyWithCount(1), false).isEmpty(), "Crafter rejected a frame ingredient");
            }
            place(helper, start.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        });
        helper.succeedWhen(() -> {
            var entities = helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds());
            helper.assertTrue(entities.stream().anyMatch(entity -> entity.getItem().is(FmpRegistries.LINK_FRAMES.get(level - 2).get())), "Native mechanical crafter did not produce the frame");
            helper.assertTrue(entities.stream().mapToInt(entity -> entity.getItem().getCount()).sum() == 1 && crafters.stream().allMatch(crafter -> crafter.getInventory().isEmpty()), "Mechanical crafting duplicated output or failed to consume its material grid");
        });
    }
    private static IItemHandler items(GameTestHelper helper, BlockPos pos) { return Objects.requireNonNull(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), Direction.UP)); }
    private static int total(IItemHandler inventory) { int count = 0; for (int slot = 0; slot < inventory.getSlots(); slot++) count += inventory.getStackInSlot(slot).getCount(); return count; }
    private static void place(GameTestHelper helper, BlockPos pos, BlockState state) { helper.getLevel().setBlockAndUpdate(helper.absolutePos(pos), state); }
    private static void power(GameTestHelper helper, BlockPos pos) {
        var state = helper.getLevel().getBlockState(helper.absolutePos(pos)); var axis = ((IRotate)state.getBlock()).getRotationAxis(state);
        var towardMachine = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE); var motor = pos.relative(towardMachine.getOpposite());
        place(helper, motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, towardMachine));
        ((CreativeMotorBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(motor))).generatedSpeed.setValue(256);
    }
}
