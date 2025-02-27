package uwu.lopyluna.create_dd.content.blocks.logistics.fluid_reservoir;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

@SuppressWarnings({"removal", "deprecated", "unchecked", "all"})
public class FluidReservoirBlockEntity extends SmartBlockEntity implements IMultiBlockEntityContainer.Fluid, IHaveGoggleInformation {

    private static final int MAX_SIZE = 3;

    protected LazyOptional<IFluidHandler> fluidCapability;
    protected FluidTank tankInventory;
    protected BlockPos controller;
    protected BlockPos lastKnownPos;
    protected boolean updateConnectivity;
    protected int luminosity;
    protected int width;
    protected int height;

    protected Axis axis;

    private static final int SYNC_RATE = 8;
    protected int syncCooldown;
    protected boolean queuedSync;

    public FluidReservoirBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        tankInventory = createInventory();
        fluidCapability = LazyOptional.of(() -> tankInventory);
        updateConnectivity = false;

        width = 1;
        height = 1;

        refreshCapability();
    }

    protected SmartFluidTank createInventory() {
        return new SmartFluidTank(getCapacityMultiplier(), this::onFluidStackChanged);
    }

    protected void updateConnectivity() {
        updateConnectivity = false;
        if (level.isClientSide)
            return;
        if (!isController())
            return;
        ConnectivityHandler.formMulti(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (syncCooldown > 0) {
            syncCooldown--;
            if (syncCooldown == 0 && queuedSync)
                sendData();
        }

        if (lastKnownPos == null)
            lastKnownPos = getBlockPos();
        else if (!lastKnownPos.equals(worldPosition) && worldPosition != null) {
            onPositionChanged();
            return;
        }

        if (updateConnectivity)
            updateConnectivity();
    }

    @Override
    public BlockPos getLastKnownPos() {
        return lastKnownPos;
    }

    @Override
    public boolean isController() {
        return controller == null || worldPosition.getX() == controller.getX()
                && worldPosition.getY() == controller.getY() && worldPosition.getZ() == controller.getZ();
    }

    @Override
    public void initialize() {
        super.initialize();
        sendData();
        if (level.isClientSide)
            invalidateRenderBoundingBox();
    }

    private void onPositionChanged() {
        removeController(true);
        lastKnownPos = worldPosition;
    }

    protected void onFluidStackChanged(FluidStack newFluidStack) {
        if (!hasLevel())
            return;

        FluidType attributes = newFluidStack.getFluid()
                .getFluidType();
        int luminosity = (int) (attributes.getLightLevel(newFluidStack) / 1.2f);
        boolean reversed = attributes.isLighterThanAir();
        int maxY = (int) ((getFillState() * height) + 1);

        for (int yOffset = 0; yOffset < height; yOffset++) {
            boolean isBright = reversed ? (height - yOffset <= maxY) : (yOffset < maxY);
            int actualLuminosity = isBright ? luminosity : luminosity > 0 ? 1 : 0;

            for (int xOffset = 0; xOffset < width; xOffset++) {
                for (int zOffset = 0; zOffset < width; zOffset++) {
                    BlockPos pos = this.worldPosition.offset(xOffset, yOffset, zOffset);
                    FluidReservoirBlockEntity tankAt = ConnectivityHandler.partAt(getType(), level, pos);
                    if (tankAt == null)
                        continue;
                    level.updateNeighbourForOutputSignal(pos, tankAt.getBlockState()
                            .getBlock());
                    if (tankAt.luminosity == actualLuminosity)
                        continue;
                    tankAt.setLuminosity(actualLuminosity);
                }
            }
        }

        if (!level.isClientSide) {
            setChanged();
            sendData();
        }
    }

    protected void setLuminosity(int luminosity) {
        if (level.isClientSide)
            return;
        if (this.luminosity == luminosity)
            return;
        this.luminosity = luminosity;
        sendData();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @SuppressWarnings("unchecked")
    @Override
    public FluidReservoirBlockEntity getControllerBE() {
        if (isController())
            return this;
        BlockEntity blockEntity = level.getBlockEntity(controller);
        if (blockEntity instanceof FluidReservoirBlockEntity)
            return (FluidReservoirBlockEntity) blockEntity;
        return null;
    }

    public void applyFluidTankSize(int blocks) {
        tankInventory.setCapacity(blocks * getCapacityMultiplier());
        int overflow = tankInventory.getFluidAmount() - tankInventory.getCapacity();
        if (overflow > 0)
            tankInventory.drain(overflow, IFluidHandler.FluidAction.EXECUTE);
    }

    public void removeController(boolean keepFluids) {
        if (level.isClientSide)
            return;
        updateConnectivity = true;
        if (!keepFluids)
            applyFluidTankSize(1);
        controller = null;
        width = 1;
        height = 1;
        onFluidStackChanged(tankInventory.getFluid());

        BlockState state = getBlockState();
        if (FluidReservoirBlock.isTank(state)) {
            state = state.setValue(FluidReservoirBlock.LARGE, false);
            getLevel().setBlock(worldPosition, state, 22);
        }

        refreshCapability();
        setChanged();
        sendData();
    }

    public void sendDataImmediately() {
        syncCooldown = 0;
        queuedSync = false;
        sendData();
    }

    @Override
    public void sendData() {
        if (syncCooldown > 0) {
            queuedSync = true;
            return;
        }
        super.sendData();
        queuedSync = false;
        syncCooldown = SYNC_RATE;
    }

    @Override
    public void setController(BlockPos controller) {
        if (level.isClientSide && !isVirtual())
            return;
        if (controller.equals(this.controller))
            return;
        this.controller = controller;
        refreshCapability();
        setChanged();
        sendData();
    }

    private void refreshCapability() {
        LazyOptional<IFluidHandler> oldCap = fluidCapability;
        fluidCapability = LazyOptional.of(() -> handlerForCapability());
        oldCap.invalidate();
    }

    private IFluidHandler handlerForCapability() {
        return isController() ? tankInventory
                : getControllerBE() != null ? getControllerBE().handlerForCapability() : new FluidTank(0);
    }

    @Override
    public BlockPos getController() {
        return isController() ? worldPosition : controller;
    }

    @Override
    protected AABB createRenderBoundingBox() {
        if (isController())
            return super.createRenderBoundingBox().expandTowards(width - 1, height - 1, width - 1);
        else
            return super.createRenderBoundingBox();
    }

    @Nullable
    public FluidReservoirBlockEntity getOtherFluidTankBlockEntity(Direction direction) {
        BlockEntity otherBE = level.getBlockEntity(worldPosition.relative(direction));
        if (otherBE instanceof FluidReservoirBlockEntity)
            return (FluidReservoirBlockEntity) otherBE;
        return null;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {

        FluidReservoirBlockEntity controllerBE = getControllerBE();
        if (controllerBE == null)
            return false;
        return containedFluidTooltip(tooltip, isPlayerSneaking,
                controllerBE.getCapability(net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY));
    }


    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);

        BlockPos controllerBefore = controller;
        int prevSize = width;
        int prevHeight = height;
        int prevLum = luminosity;

        updateConnectivity = compound.contains("Uninitialized");
        luminosity = compound.getInt("Luminosity");
        controller = null;
        lastKnownPos = null;

        if (compound.contains("LastKnownPos"))
            lastKnownPos = NbtUtils.readBlockPos(compound.getCompound("LastKnownPos"));
        if (compound.contains("Controller"))
            controller = NbtUtils.readBlockPos(compound.getCompound("Controller"));

        if (isController()) {
            width = compound.getInt("Size");
            height = compound.getInt("Height");
            tankInventory.setCapacity(getTotalTankSize() * getCapacityMultiplier());
            tankInventory.readFromNBT(compound.getCompound("TankContent"));
            if (tankInventory.getSpace() < 0)
                tankInventory.drain(-tankInventory.getSpace(), IFluidHandler.FluidAction.EXECUTE);
        }

        if (!clientPacket)
            return;
        
        boolean changeOfController =
                controllerBefore == null ? controller != null : !controllerBefore.equals(controller);
        if (changeOfController || prevSize != width || prevHeight != height) {
            if (hasLevel()) 
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 16);
            if (isController()) 
                tankInventory.setCapacity(getCapacityMultiplier() * getTotalTankSize());
            invalidateRenderBoundingBox();
        }

        if (luminosity != prevLum && hasLevel())
            level.getChunkSource()
                    .getLightEngine()
                    .checkBlock(worldPosition);
    }

    public float getFillState() {
        return (float) tankInventory.getFluidAmount() / tankInventory.getCapacity();
    }

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        if (updateConnectivity)
            compound.putBoolean("Uninitialized", true);
        if (lastKnownPos != null)
            compound.put("LastKnownPos", NbtUtils.writeBlockPos(lastKnownPos));
        if (!isController())
            compound.put("Controller", NbtUtils.writeBlockPos(controller));
        if (isController()) {
            compound.put("TankContent", tankInventory.writeToNBT(new CompoundTag()));
            compound.putInt("Size", width);
            compound.putInt("Height", height);
        }
        compound.putInt("Luminosity", luminosity);
        super.write(compound, clientPacket);

        if (!clientPacket)
            return;
        if (queuedSync)
            compound.putBoolean("LazySync", true);
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (!fluidCapability.isPresent())
            refreshCapability();
        if (cap == net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
            return fluidCapability.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidate() {
        super.invalidate();
    }

    public IFluidTank getTankInventory() {
        return tankInventory;
    }

    public int getMaxSize() {
        return MAX_SIZE;
    }

    public int getTotalTankSize() {
        return width * width * height;
    }

    public static int getCapacityMultiplier() {
        return AllConfigs.server().fluids.fluidTankCapacity.get() * 1000;
    }

    public static int getMaxHeight() {
        return AllConfigs.server().fluids.fluidTankMaxHeight.get();
    }

    @Override
    public void preventConnectivityUpdate() { updateConnectivity = false; }

    @Override
    public void notifyMultiUpdated() {
        BlockState state = this.getBlockState();
        if (FluidReservoirBlock.isTank(state)) { // safety
            level.setBlock(getBlockPos(), state.setValue(FluidReservoirBlock.LARGE, width > 2), 6);
        }

        onFluidStackChanged(tankInventory.getFluid());
        setChanged();
    }

    @Override
    public Axis getMainConnectionAxis() {
        return getMainAxisOf(this);
    }

    @Override
    public int getMaxLength(Axis longAxis, int width) {
        if (longAxis == Axis.Y) return getMaxWidth();
        return getMaxHeight();
    }

    @Override
    public int getMaxWidth() {
        return MAX_SIZE;
    }

    @Override
    public int getHeight() { return height; }

    @Override
    public int getWidth() { return width; }

    @Override
    public void setHeight(int height) { this.height = height; }

    @Override
    public void setWidth(int width) { this.width = width; }

    @Override
    public boolean hasTank() {
        return true;
    }

    @Override
    public int getTankSize(int tank) {
        return getCapacityMultiplier();
    }

    @Override
    public void setTankSize(int tank, int blocks) {
        applyFluidTankSize(blocks);
    }

    @Override
    public IFluidTank getTank(int tank) {
        return tankInventory;
    }

    @Override
    public FluidStack getFluid(int tank) {
        return tankInventory.getFluid()
                .copy();
    }
}