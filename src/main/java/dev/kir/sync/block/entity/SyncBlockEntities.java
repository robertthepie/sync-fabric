package dev.kir.sync.block.entity;

import com.neep.neepmeat.transport.api.pipe.BloodAcceptor;
import dev.kir.sync.block.SyncBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registry;

public final class SyncBlockEntities {
    public static final BlockEntityType<ShellStorageBlockEntity> SHELL_STORAGE;
    public static final BlockEntityType<ShellConstructorBlockEntity> SHELL_CONSTRUCTOR;
    public static final BlockEntityType<TreadmillBlockEntity> TREADMILL;

    static {
        SHELL_STORAGE = register(ShellStorageBlockEntity::new, SyncBlocks.SHELL_STORAGE);
        SHELL_CONSTRUCTOR = register(ShellConstructorBlockEntity::new, SyncBlocks.SHELL_CONSTRUCTOR);
        TREADMILL = register(TreadmillBlockEntity::new, SyncBlocks.TREADMILL);
    }

    public static void init() {
        BloodAcceptor.SIDED.registerForBlockEntity(ShellStorageBlockEntity::getBloodAcceptor, SHELL_STORAGE);

    }

    private static <T extends BlockEntity> BlockEntityType<T> register(BlockEntityType.BlockEntityFactory<T> factory, Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        return Registry.register(Registries.BLOCK_ENTITY_TYPE, id, BlockEntityType.Builder.create(factory, block).build(null));
    }
}
