package dev.kir.sync.block.entity;

import com.neep.neepmeat.api.machine.MotorisedBlock;
import com.neep.neepmeat.transport.api.pipe.AbstractBloodAcceptor;
import com.neep.neepmeat.transport.api.pipe.BloodAcceptor;
import com.neep.neepmeat.transport.block.energy_transport.VascularConduitBlock;
import dev.kir.sync.Sync;
import dev.kir.sync.api.event.PlayerSyncEvents;
import dev.kir.sync.api.shell.ShellStateContainer;
import dev.kir.sync.block.AbstractShellContainerBlock;
import dev.kir.sync.block.ShellStorageBlock;
import dev.kir.sync.client.gui.ShellSelectorGUI;
import dev.kir.sync.util.BlockPosUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.DyeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;


public class ShellStorageBlockEntity extends AbstractShellContainerBlockEntity implements MotorisedBlock.DiagnosticsProvider {
    private EntityState entityState;
    private int ticksWithoutPower;
    private final BooleanAnimator connectorAnimator;
    private boolean powered = Sync.getConfig().storageEJNeeds() <= 0;
    private float powerConsumption = Sync.getConfig().storageEJNeeds() / 1000.0f;

    protected BloodAcceptor bloodAcceptor;
    public BloodAcceptor getBloodAcceptor(Direction ignoredFace) {
        if (powerConsumption == 0 ) return null;
        if (bloodAcceptor == null) {
            var bottomHalf = (ShellStorageBlockEntity) getBottomPart().orElse(null);
            if (bottomHalf == null) return null;
            if (bottomHalf.bloodAcceptor != null) {
                this.bloodAcceptor = bottomHalf.bloodAcceptor;
            } else {
                bottomHalf.bloodAcceptor = bloodAcceptor = new AbstractBloodAcceptor() {
                    public float updateInflux(float influx) {
                        boolean sufficientPower = influx >= powerConsumption;

                        ShellStorageBlockEntity bottom = (ShellStorageBlockEntity) getBottomPart().orElse(null);
                        ShellStorageBlockEntity top = (ShellStorageBlockEntity) getTopPart().orElse(null);
                        if (bottom == null || top == null) return 0;

                        bottom.powered = top.powered = sufficientPower;
                        if (sufficientPower) {
                            bottom.ticksWithoutPower = 0;
                            return powerConsumption;
                        }
                        return 0.0f;
                    }

                    public BloodAcceptor.Mode getMode() {
                        return Mode.SINK;
                    }
                };

            }
        }
        return bloodAcceptor;
    }



    public ShellStorageBlockEntity(BlockPos pos, BlockState state) {
        super(SyncBlockEntities.SHELL_STORAGE, pos, state);
        this.entityState = EntityState.NONE;
        this.connectorAnimator = new BooleanAnimator(false);
    }

    public DyeColor getIndicatorColor() {
        if (this.world != null && ShellStorageBlock.isPowered(this.getCachedState())) {
            return this.color == null ? DyeColor.LIME : this.color;
        }

        return DyeColor.RED;
    }

    @Environment(EnvType.CLIENT)
    public float getConnectorProgress(float tickDelta) {
        return this.getBottomPart().map(x -> ((ShellStorageBlockEntity)x).connectorAnimator.getProgress(tickDelta)).orElse(0f);
    }

    @Override
    public void onServerTick(World world, BlockPos pos, BlockState state) {
        super.onServerTick(world, pos, state);

        boolean shouldBeOpen = powered && this.getBottomPart().map(x -> x.shell == null).orElse(true);

        ShellStorageBlock.setPowered(state, world, pos, powered);
        ShellStorageBlock.setOpen(state, world, pos, shouldBeOpen);

        if (!powered && ticksWithoutPower++ > Sync.getConfig().shellStorageMaxUnpoweredLifespan())
            this.destroyShell((ServerWorld)world, pos);
    }

    @Override
    public void onClientTick(World world, BlockPos pos, BlockState state) {
        super.onClientTick(world, pos, state);
        this.connectorAnimator.setValue(this.shell != null);
        this.connectorAnimator.step();
        if (this.entityState == EntityState.LEAVING || this.entityState == EntityState.CHILLING) {
            this.entityState = BlockPosUtil.hasPlayerInside(pos, world) ? this.entityState : EntityState.NONE;
        }
    }

    @Environment(EnvType.CLIENT)
    public void onEntityCollisionClient(Entity entity, BlockState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(entity instanceof PlayerEntity player)) {
            return;
        }

        if (this.entityState == EntityState.NONE) {
            boolean isInside = BlockPosUtil.isEntityInside(entity, this.pos);
            PlayerSyncEvents.ShellSelectionFailureReason failureReason = !isInside && client.player == entity ? PlayerSyncEvents.ALLOW_SHELL_SELECTION.invoker().allowShellSelection(player, this) : null;
            this.entityState = isInside || failureReason != null ? EntityState.CHILLING : EntityState.ENTERING;
            if (failureReason != null) {
                player.sendMessage(failureReason.toText(), true);
            }
        } else if (this.entityState != EntityState.CHILLING && client.currentScreen == null) {
            BlockPosUtil.moveEntity(entity, this.pos, state.get(ShellStorageBlock.FACING), this.entityState == EntityState.ENTERING);
        }

        if (this.entityState == EntityState.ENTERING && client.player == entity && client.currentScreen == null && BlockPosUtil.isEntityInside(entity, this.pos)) {
            client.setScreen(new ShellSelectorGUI(() -> this.entityState = EntityState.LEAVING, () -> this.entityState = EntityState.CHILLING));
        }
    }

    @Override
    public ActionResult onUse(World world, BlockPos pos, PlayerEntity player, Hand hand) {
        if (VascularConduitBlock.matches(player.getStackInHand(hand))) {
            return ActionResult.PASS;
        }

        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        ItemStack stack = player.getStackInHand(hand);
        Item item = stack.getItem();
        if (stack.getCount() > 0 && item instanceof DyeItem dye) {
            stack.decrement(1);
            this.color = dye.getColor();
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public MotorisedBlock.@Nullable Diagnostics getDiagnostics() {
        return MotorisedBlock.Diagnostics.insufficientPower(!powered, 0.0f, powerConsumption);
    }

    private enum EntityState {
        NONE,
        ENTERING,
        CHILLING,
        LEAVING
    }

    static {
        ShellStateContainer.LOOKUP.registerForBlockEntity((x, s) -> x.hasWorld() && AbstractShellContainerBlock.isBottom(x.getCachedState()) && (s == null || s.equals(x.getShellState())) ? x : null, SyncBlockEntities.SHELL_STORAGE);
    }
}