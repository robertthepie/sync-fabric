package dev.kir.sync.block.entity;

import com.google.common.base.Suppliers;
import com.neep.neepmeat.init.NMFluids;
import com.neep.neepmeat.transport.item_network.RetrievalTarget;
import com.neep.neepmeat.util.ItemUtil;
import dev.kir.sync.util.BlockPosUtil;
import dev.kir.sync.api.shell.ShellState;
import dev.kir.sync.api.shell.ShellStateContainer;
import dev.kir.sync.api.event.PlayerSyncEvents;
import dev.kir.sync.block.AbstractShellContainerBlock;
import dev.kir.sync.block.ShellConstructorBlock;
import dev.kir.sync.config.SyncConfig;
import dev.kir.sync.entity.damage.FingerstickDamageSource;
import dev.kir.sync.Sync;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.impl.transfer.fluid.CauldronStorage;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

import static dev.kir.sync.block.AbstractShellContainerBlock.HALF;

@SuppressWarnings({"UnstableApiUsage"})
public class ShellConstructorBlockEntity extends AbstractShellContainerBlockEntity {

    private static int constructorLiquidGoal = Sync.getConfig().constructorLiquidNeeds();
    private static int constructorLiquidNeeds = Math.round(Sync.getConfig().constructorLiquidNeeds() / (Sync.getConfig().constructorProgressPer1000() * 20));

    private int progress = 0;

    public ShellConstructorBlockEntity(BlockPos pos, BlockState state) {
        super(SyncBlockEntities.SHELL_CONSTRUCTOR, pos, state);

        if (ShellConstructorBlock.isBottom(state)) {
            storageCaches = Suppliers.memoize(() ->
            {
                ObjectArrayList<RetrievalTarget<FluidVariant>> list = new ObjectArrayList<>();

                BlockPos toPos = getPos().add(0, 1, 0);
                for (Direction direction : Direction.values())
                {
                    if (direction.getAxis().isVertical()) {
                        if (direction == Direction.UP) {
                            list.add(RetrievalTarget.of(FluidStorage.SIDED, (ServerWorld) getWorld(), toPos.offset(direction), direction.getOpposite()));
                        } else {
                            list.add(RetrievalTarget.of(FluidStorage.SIDED, (ServerWorld) getWorld(), getPos().offset(direction), direction.getOpposite()));
                        }
                        continue;
                    }

                    list.add(RetrievalTarget.of(FluidStorage.SIDED, (ServerWorld) getWorld(), getPos().offset(direction), direction.getOpposite()));
                    list.add(RetrievalTarget.of(FluidStorage.SIDED, (ServerWorld) getWorld(), toPos.offset(direction), direction.getOpposite()));
                }
                return new ObjectImmutableList<>(list);
            });
        } else {
            storageCaches = null;
        }
    }

    private final Supplier<List<RetrievalTarget<FluidVariant>>> storageCaches;

    @Nullable private List<Storage<FluidVariant>> adjacentStorageCache;
    public List<Storage<FluidVariant>> getAdjacentStorages()
    {
        // Build the cache from the other cache
        if (adjacentStorageCache == null)
        {
            adjacentStorageCache = new ObjectArrayList<>();
            for (var cache : storageCaches.get())
            {
                Storage<FluidVariant> storage = cache.find();
                if (storage != null)
                {
                    // Cauldron storages hate the mixer, and we stole this code from there.
                    if (storage instanceof CauldronStorage)
                        continue;

                    adjacentStorageCache.add(storage);
                }
            }
        }

        return adjacentStorageCache;
    }

    private long tryDrain() {
        long work_fluid_amount_extracted = 0;
        long ammountToExtract = Math.min(constructorLiquidNeeds, constructorLiquidGoal - progress);
        try (Transaction transaction = Transaction.openOuter()) {
            List<Storage<FluidVariant>> inputList = getAdjacentStorages();
            if (inputList.isEmpty()) {
                transaction.abort();
                return 0;
            }

            Storage<FluidVariant> combinedStorage = new CombinedStorage<>(inputList);

            try (Transaction inner = ((TransactionContext) transaction).openNested()) {
                work_fluid_amount_extracted = combinedStorage.extract(NMFluids.WORK_FLUID.variant(), ammountToExtract, inner);
                long slurry_amount_extracted = combinedStorage.extract(NMFluids.TISSUE_SLURRY.variant(), work_fluid_amount_extracted, transaction);
                if (slurry_amount_extracted < work_fluid_amount_extracted) {
                    inner.abort();
                    work_fluid_amount_extracted = combinedStorage.extract(NMFluids.WORK_FLUID.variant(), slurry_amount_extracted, transaction);
                } else {
                    inner.commit();
                }
            }
            transaction.commit();

        }
        return work_fluid_amount_extracted;
    }

    @Override
    public void onServerTick(World world, BlockPos pos, BlockState state) {


        super.onServerTick(world, pos, state);
        if (ShellConstructorBlock.isOpen(state)) {
            ShellConstructorBlock.setOpen(state, world, pos, BlockPosUtil.hasPlayerInside(pos, world));
        }
        DoubleBlockHalf half = getCachedState().get(HALF);
        if (half == DoubleBlockHalf.UPPER) return;


        if (shell == null) return;
        if (progress >= constructorLiquidGoal) return;

        long leftoverProgress = tryDrain();
        if (leftoverProgress > 0) {
            progress += (int) leftoverProgress;
            shell.setProgress((float) progress / constructorLiquidGoal);
        }
        adjacentStorageCache = null;


    }

    @Override
    public ActionResult onUse(World world, BlockPos pos, PlayerEntity player, Hand hand) {
        if (ItemUtil.playerHoldingPipe(player, hand))
            return ActionResult.PASS;

        PlayerSyncEvents.ShellConstructionFailureReason failureReason = this.beginShellConstruction(player);
        if (failureReason == null) {
            return ActionResult.SUCCESS;
        } else {
            player.sendMessage(failureReason.toText(), true);
            return ActionResult.CONSUME;
        }
    }

    @Nullable
    private PlayerSyncEvents.ShellConstructionFailureReason beginShellConstruction(PlayerEntity player) {
        PlayerSyncEvents.ShellConstructionFailureReason failureReason = this.shell == null
                ? PlayerSyncEvents.ALLOW_SHELL_CONSTRUCTION.invoker().allowShellConstruction(player, this)
                : PlayerSyncEvents.ShellConstructionFailureReason.OCCUPIED;

        if (failureReason != null) {
            return failureReason;
        }

        if (player instanceof ServerPlayerEntity serverPlayer) {
            SyncConfig config = Sync.getConfig();

            float damage = serverPlayer.server.isHardcore() ? config.hardcoreFingerstickDamage() : config.fingerstickDamage();

            boolean isCreative = !serverPlayer.interactionManager.getGameMode().isSurvivalLike();
            boolean isLowOnHealth = (player.getHealth() + player.getAbsorptionAmount()) <= damage;
            boolean hasTotemOfUndying = player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) || player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
            if (isLowOnHealth && !isCreative && !hasTotemOfUndying && config.warnPlayerInsteadOfKilling()) {
                return PlayerSyncEvents.ShellConstructionFailureReason.NOT_ENOUGH_HEALTH;
            }

            player.damage(world.getDamageSources().sweetBerryBush(), damage);
            this.shell = ShellState.empty(serverPlayer, pos);
            if (isCreative && config.enableInstantShellConstruction()) {
                progress = constructorLiquidGoal;
                this.shell.setProgress(ShellState.PROGRESS_DONE);
            } else progress = 0;
        }
        return null;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("fluidProgress", progress);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        progress = nbt.contains("fluidProgress") ? nbt.getInt("fluidProgress") : 0;
    }


    static {
        ShellStateContainer.LOOKUP.registerForBlockEntity((x, s) -> x.hasWorld() && AbstractShellContainerBlock.isBottom(x.getCachedState()) && (s == null || s.equals(x.getShellState())) ? x : null, SyncBlockEntities.SHELL_CONSTRUCTOR);
    }
}
