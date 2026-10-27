package fi.dy.masa.minihud.renderer;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import com.google.common.base.Predicates;
import fi.dy.masa.minihud.util.LightLevelChunkInfo;
import fi.dy.masa.minihud.util.LightLevelInfo;
import fi.dy.masa.minihud.renderer.shapes.ShapeManager;
import fi.dy.masa.minihud.renderer.shapes.ShapeSpawnSphere;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.*;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import fi.dy.masa.malilib.config.IConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.texture.MaLiLibComplexBinding;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.malilib.util.position.PositionUtils;
import fi.dy.masa.malilib.util.position.Vec3d;
import fi.dy.masa.minihud.MiniHUD;
import fi.dy.masa.minihud.Reference;
import fi.dy.masa.minihud.config.Configs;
import fi.dy.masa.minihud.config.RendererToggle;
import fi.dy.masa.minihud.util.LightLevelMarkerMode;
import fi.dy.masa.minihud.util.LightLevelNumberMode;
import fi.dy.masa.minihud.util.LightLevelRenderCondition;

public class OverlayRendererLightLevel extends OverlayRendererBase
{
    public static final OverlayRendererLightLevel INSTANCE = new OverlayRendererLightLevel();
    private static final Identifier TEXTURE_NUMBERS = Identifier.fromNamespaceAndPath(Reference.MOD_ID, "textures/misc/light_level_numbers.png");

    private final Timer timer;
    private final ChunkLightInfoMap lightInfos;
    private BlockPos.MutableBlockPos mutablePos;
    private Direction lastDirection;
    private List<ShapeSpawnSphere> lastSpawnSpheres;

    private boolean ignoreRange;
    private boolean tagsBroken;
    private boolean needsUpdate;
    private boolean needsDataUpdate;

    // if implemented as method param, it'll break tweakermore.
    private boolean underWater;

    protected OverlayRendererLightLevel()
    {
        this.timer = new Timer();
        this.lightInfos = new ChunkLightInfoMap();
        this.mutablePos = new BlockPos.MutableBlockPos();
        this.lastDirection = Direction.NORTH;
        this.needsDataUpdate = true;
    }

    @Override
    public String getName()
    {
        return "LightLevel";
    }

    public void markNeedsUpdate(boolean clearData)
    {
        if (clearData)
        {
            this.needsDataUpdate = true;
            this.lightInfos.clear();
        }

        this.needsUpdate = true;
        // Clean buffers when receiving the RenderCallback.
        this.clearBuffers();
    }

    public void markChunkChanged(int cx, int cz)
    {
        this.needsUpdate = true;
        this.needsDataUpdate = true;
        this.lightInfos.removeNeighbors(cx, cz);
    }

    public void onSpawnSphereUpdate()
    {
        boolean hasShapeInteraction = Configs.Generic.LIGHT_LEVEL_SPAWN_SPHERE_FULL.getBooleanValue() ||
                Configs.Generic.LIGHT_LEVEL_SPAWN_SPHERE_LIMIT.getBooleanValue();

        if (hasShapeInteraction)
        {
            this.markNeedsUpdate(true);
        }
    }

    @Override
    public boolean shouldRender(Minecraft mc)
    {
        return RendererToggle.OVERLAY_LIGHT_LEVEL.getBooleanValue();
    }

    @Override
    public boolean needsUpdate(Entity entity, Minecraft mc)
    {
        if (this.needsDataUpdate || this.lastUpdatePos == null)
        {
            this.needsDataUpdate = true;
            return true;
        }

        int dist = PositionUtils.getEntityBlockPos(entity).distManhattan(this.lastUpdatePos);
        if (dist >= this.timer.getMinDist())
        {
            this.needsDataUpdate = true;
            return true;
        }

        return this.needsUpdate ||
                (Configs.Generic.LIGHT_LEVEL_NUMBER_ROTATION.getBooleanValue() &&
                        Configs.Generic.LIGHT_LEVEL_NUMBER_CONDITION.getOptionListValue() != LightLevelRenderCondition.NEVER &&
                        this.lastDirection != entity.getDirection());
    }

    @Override
    public void update(Vec3d cameraPos, Entity entity, Minecraft mc, ProfilerFiller profiler)
    {
        if (mc.level == null)
        {
            this.needsUpdate = false;
            this.needsDataUpdate = false;
            return;
        }

        this.timer.timeUpdate();
        BlockPos pos = PositionUtils.getEntityBlockPos(entity);
        if (this.needsDataUpdate)
        {
            this.updateLightLevels(mc.level, pos);
        }
        this.renderThrough = Configs.Generic.LIGHT_LEVEL_RENDER_THROUGH.getBooleanValue();

        this.timer.timeRender();
        if (this.hasData())
        {
            this.render(cameraPos, mc, profiler);
        }

        this.timer.timeEnd();
        this.timer.debugLog();

        this.lastUpdatePos = pos;
        this.lastDirection = entity.getDirection();
        this.needsUpdate = false;
        this.needsDataUpdate = false;
    }

    @Override
    public boolean hasData()
    {
        return this.lightInfos.isEmpty() == false;
    }

    @Override
    public void allocateBuffers()
    {
        // Don't reallocate it unless empty; using start() calls reset() anyways.
        if (this.renderObjects.isEmpty())
        {
            this.renderObjects.add(new RenderObjectVbo(() -> this.getName() + " Quads", MaLiLibPipelines.POSITION_TEX_COLOR_MASA_LEQUAL_DEPTH_NO_CULL, 0));
            this.renderObjects.add(new RenderObjectVbo(() -> this.getName() + " Lines", MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH, 0));
        }
    }

    @Override
    public void render(Vec3d cameraPos, Minecraft mc, ProfilerFiller profiler)
    {
        this.allocateBuffers();
        this.renderTexQuads(cameraPos, mc, profiler);
        this.renderOutlines(cameraPos, mc, profiler);
    }

    private void renderTexQuads(Vec3d cameraPos, Minecraft mc, ProfilerFiller profiler)
    {
        if (mc.level == null || mc.player == null)
        {
            return;
        }

        LightLevelNumberMode numberMode = (LightLevelNumberMode) Configs.Generic.LIGHT_LEVEL_NUMBER_MODE.getOptionListValue();
        LightLevelRenderCondition condition = (LightLevelRenderCondition) Configs.Generic.LIGHT_LEVEL_NUMBER_CONDITION.getOptionListValue();
        if (numberMode == LightLevelNumberMode.NONE || condition == LightLevelRenderCondition.NEVER)
        {
            return;
        }

        profiler.push("light_level_quads");
        int safeThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_SAFE.getIntegerValue();
        int dimThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_DIM.getIntegerValue();
        Direction numberFacing = Configs.Generic.LIGHT_LEVEL_NUMBER_ROTATION.getBooleanValue()
                ? Objects.requireNonNullElse(mc.getCameraEntity(), mc.player).getDirection()
                : Direction.NORTH;
        boolean useColoredNumbers = Configs.Generic.LIGHT_LEVEL_COLORED_NUMBERS.getBooleanValue();

        // this.renderThrough ? MaLiLibPipelines.POSITION_TEX_COLOR_SIMPLE : MaLiLibPipelines.POSITION_TEX_COLOR_LESSER_DEPTH
        RenderObjectVbo ctx = this.renderObjects.getFirst();
        BufferBuilder builder = ctx.start(() -> "minihud:light_level/tex_quads", this.renderThrough ? MaLiLibPipelines.POSITION_TEX_COLOR_MASA_NO_DEPTH_NO_CULL : MaLiLibPipelines.POSITION_TEX_COLOR_MASA_LEQUAL_DEPTH_NO_CULL, 0);
        PoseStack matrices = new PoseStack();

        try
        {
//            ctx.bindTexture(TEXTURE_NUMBERS, 0, 256, 256);
            ctx.prepareComplexTextures(List.of(new MaLiLibComplexBinding("Sampler0", TEXTURE_NUMBERS, () -> null)));
        }
        catch (Exception err)
        {
            MiniHUD.LOGGER.error("bindTexture Exception: {}", err.getMessage());
            return;
        }

        matrices.pushPose();
        PoseStack.Pose e = matrices.last();

        if (numberMode == LightLevelNumberMode.BLOCK || numberMode == LightLevelNumberMode.BOTH)
        {
            this.renderNumbers(cameraPos, LightLevelNumberMode.BLOCK,
                               Configs.Generic.LIGHT_LEVEL_NUMBER_OFFSET_BLOCK_X,
                               Configs.Generic.LIGHT_LEVEL_NUMBER_OFFSET_BLOCK_Y,
                               Configs.Colors.LIGHT_LEVEL_NUMBER_BLOCK_LIT,
                               Configs.Colors.LIGHT_LEVEL_NUMBER_BLOCK_DIM,
                               Configs.Colors.LIGHT_LEVEL_NUMBER_BLOCK_DARK,
                               useColoredNumbers, safeThreshold, dimThreshold, numberFacing, builder, e);
        }

        if (numberMode == LightLevelNumberMode.SKY || numberMode == LightLevelNumberMode.BOTH)
        {
            this.renderNumbers(cameraPos, LightLevelNumberMode.SKY,
                               Configs.Generic.LIGHT_LEVEL_NUMBER_OFFSET_SKY_X,
                               Configs.Generic.LIGHT_LEVEL_NUMBER_OFFSET_SKY_Y,
                               Configs.Colors.LIGHT_LEVEL_NUMBER_SKY_LIT,
                               Configs.Colors.LIGHT_LEVEL_NUMBER_SKY_DIM,
                               Configs.Colors.LIGHT_LEVEL_NUMBER_SKY_DARK,
                               useColoredNumbers, safeThreshold, dimThreshold, numberFacing, builder, e);
        }

        try
        {
            MeshData meshData = builder.build();

            if (meshData != null)
            {
                ctx.upload(meshData, false);
                meshData.close();
            }
        }
        catch (Exception err)
        {
            MiniHUD.LOGGER.error("OverlayRendererLightLevel#renderQuads(): Exception; {}", err.getMessage());
        }

        matrices.popPose();
        profiler.pop();
    }

    private void renderOutlines(Vec3d cameraPos, Minecraft mc, ProfilerFiller profiler)
    {
        if (mc.level == null || mc.player == null)
        {
            return;
        }

        LightLevelMarkerMode markerMode = (LightLevelMarkerMode) Configs.Generic.LIGHT_LEVEL_MARKER_MODE.getOptionListValue();
        LightLevelRenderCondition condition = (LightLevelRenderCondition) Configs.Generic.LIGHT_LEVEL_MARKER_CONDITION.getOptionListValue();
        if (markerMode == LightLevelMarkerMode.NONE || condition == LightLevelRenderCondition.NEVER)
        {
            return;
        }

        profiler.push("light_level_outlines");
        int safeThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_SAFE.getIntegerValue();
        int dimThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_DIM.getIntegerValue();

        RenderObjectVbo ctx = this.renderObjects.get(1);
        // According to spark, could save ~10% cpu time by having a way to set initial ByteBufferBuilder capacity.
        BufferBuilder builder = ctx.start(() -> "minihud:light_level/outlines", this.renderThrough ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH, 0);

        if (markerMode == LightLevelMarkerMode.SQUARE)
        {
            this.renderMarkers(this::renderLightLevelSquare, cameraPos, safeThreshold, dimThreshold, this.glLineWidth, builder);
        }
        else if (markerMode == LightLevelMarkerMode.CROSS)
        {
            this.renderMarkers(this::renderLightLevelCross, cameraPos, safeThreshold, dimThreshold, this.glLineWidth, builder);
        }

        try
        {
            MeshData meshData = builder.build();

            if (meshData != null)
            {
                ctx.upload(meshData, false);
                meshData.close();
            }
        }
        catch (Exception err)
        {
            MiniHUD.LOGGER.error("OverlayRendererLightLevel#renderOutlines(): Exception; {}", err.getMessage());
        }

        profiler.pop();
    }

    @Override
    public void reset()
    {
        super.reset();
        this.tagsBroken = false;
        this.lightInfos.clear();
        this.mutablePos = new BlockPos.MutableBlockPos();
        this.lastDirection = Direction.NORTH;
        this.needsDataUpdate = true;
    }

    @SuppressWarnings({"EnhancedSwitchMigration", "DuplicateBranchesInSwitch"})
    private void renderNumbers(Vec3d cameraPos,
                               LightLevelNumberMode mode,
                               IConfigDouble cfgOffX,
                               IConfigDouble cfgOffZ,
                               ConfigColor cfgColorLit,
                               ConfigColor cfgColorDim,
                               ConfigColor cfgColorDark,
                               boolean useColoredNumbers,
                               int safeThreshold,
                               int dimThreshold,
                               Direction numberFacing,
                               BufferBuilder buffer,
                               PoseStack.Pose e)
    {
        double ox = cfgOffX.getDoubleValue();
        double oz = cfgOffZ.getDoubleValue();
        double tmpX, tmpZ;
        double offsetY = Configs.Generic.LIGHT_LEVEL_RENDER_OFFSET.getDoubleValue();
        Color4f colorLit, colorDim, colorDark;

        switch (numberFacing)
        {
            case NORTH: tmpX = -ox; tmpZ = -oz; break;
            case SOUTH: tmpX =  ox; tmpZ =  oz; break;
            case WEST:  tmpX = -oz; tmpZ =  ox; break;
            case EAST:  tmpX =  oz; tmpZ = -ox; break;
            default:    tmpX = -ox; tmpZ = -oz; break;
        }

        if (useColoredNumbers)
        {
            colorLit = cfgColorLit.getColor();
            colorDim = cfgColorDim.getColor();
            colorDark = cfgColorDark.getColor();
        }
        else
        {
            colorLit = Color4f.fromColor(0xFFFFFFFF);
            colorDim = colorLit;
            colorDark = colorLit;
        }

        this.renderLightLevelNumbers(tmpX + cameraPos.x, cameraPos.y - offsetY, tmpZ + cameraPos.z, numberFacing,
                                     safeThreshold, dimThreshold, mode, colorLit, colorDim, colorDark, buffer, e);
    }

    private void renderMarkers(IMarkerRenderer renderer,
                               Vec3d cameraPos,
                               int safeThreshold,
                               int dimThreshold,
							   float lineWidth,
                               BufferBuilder buffer)
    {
        Color4f colorBlockLit = Configs.Colors.LIGHT_LEVEL_MARKER_BLOCK_LIT.getColor();
        Color4f colorDim = Configs.Colors.LIGHT_LEVEL_MARKER_DIM.getColor();
        Color4f colorSkyLit = Configs.Colors.LIGHT_LEVEL_MARKER_SKY_LIT.getColor();
        Color4f colorDark = Configs.Colors.LIGHT_LEVEL_MARKER_DARK.getColor();
        LightLevelRenderCondition condition = (LightLevelRenderCondition) Configs.Generic.LIGHT_LEVEL_MARKER_CONDITION.getOptionListValue();
        double markerSize = Configs.Generic.LIGHT_LEVEL_MARKER_SIZE.getDoubleValue();
        double offsetX = cameraPos.x;
        double offsetY = cameraPos.y - Configs.Generic.LIGHT_LEVEL_RENDER_OFFSET.getDoubleValue();
        double offsetZ = cameraPos.z;
        double offset1 = (1.0 - markerSize) / 2.0;
        double offset2 = (1.0 - offset1);
        Color4f color;
        int range = this.ignoreRange ? Integer.MAX_VALUE : Configs.Generic.LIGHT_LEVEL_RANGE.getIntegerValue();
        int minY = this.ignoreRange ? Integer.MIN_VALUE : (int) Math.floor(offsetY - range);

        Predicate<Integer> blockLightPredicate = b -> condition.shouldRender(b, dimThreshold, safeThreshold);

        for (LightLevelChunkInfo chunk : this.lightInfos.values())
        {
            if (chunk.isEmpty())
            {
                continue;
            }

            boolean checkXZ = this.ignoreRange == false &&
                   (chunk.x < offsetX - range ||
                    chunk.x + 16 > offsetX + range ||
                    chunk.z < offsetZ - range ||
                    chunk.z + 16 > offsetZ + range);
            if (checkXZ && (
                    chunk.x > offsetX + range ||
                    chunk.x + 16 < offsetX - range ||
                    chunk.z > offsetZ + range ||
                    chunk.z + 16 < offsetZ - range))
            {
                continue;
            }
            for (LightLevelInfo info : chunk.iterable(minY, blockLightPredicate))
            {
                double x = info.x - offsetX;
                double y = info.y - offsetY;
                double z = info.z - offsetZ;
                if (checkXZ && (Math.abs(x) > range || Math.abs(z) > range) || y > range)
                {
                    continue;
                }

                if (info.block < safeThreshold)
                {
                    color = info.sky >= safeThreshold ? colorSkyLit : colorDark;
                }
                else if (info.block > dimThreshold)
                {
                    color = colorBlockLit;
                }
                else
                {
                    color = colorDim;
                }

                renderer.render((float) x, (float) y, (float) z, color, (float) offset1, (float) offset2, lineWidth, buffer);
            }
        }
    }

    private void renderLightLevelNumbers(double dx, double dy, double dz,
                                         Direction facing,
                                         int safeThreshold,
                                         int dimThreshold,
                                         LightLevelNumberMode numberMode,
                                         Color4f colorLit,
                                         Color4f colorDim,
                                         Color4f colorDark,
                                         BufferBuilder buffer,
                                         PoseStack.Pose e)
    {
        LightLevelRenderCondition condition = (LightLevelRenderCondition) Configs.Generic.LIGHT_LEVEL_NUMBER_CONDITION.getOptionListValue();
        Color4f color;
        int range = Configs.Generic.LIGHT_LEVEL_NUMBER_RANGE.getIntegerValue();
        int minY = (int) Math.floor(dy - range);

        Predicate<Integer> blockLightPredicate = b -> condition.shouldRender(b, dimThreshold, safeThreshold);

        for (LightLevelChunkInfo chunk : this.lightInfos.values())
        {
            if (chunk.isEmpty())
            {
                continue;
            }

            boolean checkXZ = chunk.x < dx - range ||
                    chunk.x + 16 > dx + range ||
                    chunk.z < dz - range ||
                    chunk.z + 16 > dz + range;
            if (checkXZ && (
                    chunk.x > dx + range ||
                    chunk.x + 16 < dx - range ||
                    chunk.z > dz + range ||
                    chunk.z + 16 < dz - range))
            {
                continue;
            }
            for (LightLevelInfo info : chunk.iterable(minY, blockLightPredicate))
            {
                double x = info.x - dx;
                double y = info.y - dy;
                double z = info.z - dz;
                if (checkXZ && (Math.abs(x) > range || Math.abs(z) > range) || y > range)
                {
                    continue;
                }
                int lightLevel = numberMode == LightLevelNumberMode.BLOCK ? info.block : info.sky;

                if (lightLevel < safeThreshold)
                {
                    color = colorDark;
                }
                else if (lightLevel > dimThreshold)
                {
                    color = colorLit;
                }
                else
                {
                    color = colorDim;
                }

                this.renderLightLevelTextureColor((float) x, (float) y, (float) z, facing, lightLevel, color, buffer, e);
            }
        }
    }

    private void renderLightLevelTextureColor(float x, float y, float z, Direction facing, int lightLevel, Color4f color, BufferBuilder buffer, PoseStack.Pose e)
    {
        float w = 0.25f;
        float u = (lightLevel & 0x3) * w;
        float v = (lightLevel >> 2) * w;
        y += 0.005F;

        Matrix4f m = e.pose();

        switch (facing)
        {
            case NORTH:
                buffer.addVertex(m, x    , y, z    ).setUv(u    , v    ).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x    , y, z + 1).setUv(u    , v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z + 1).setUv(u + w, v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z    ).setUv(u + w, v    ).setColor(color.r, color.g, color.b, color.a);
                break;

            case SOUTH:
                buffer.addVertex(m, x + 1, y, z + 1).setUv(u    , v    ).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z    ).setUv(u    , v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x    , y, z    ).setUv(u + w, v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x    , y, z + 1).setUv(u + w, v    ).setColor(color.r, color.g, color.b, color.a);
                break;

            case EAST:
                buffer.addVertex(m, x + 1, y, z    ).setUv(u    , v    ).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x    , y, z    ).setUv(u    , v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x    , y, z + 1).setUv(u + w, v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z + 1).setUv(u + w, v    ).setColor(color.r, color.g, color.b, color.a);
                break;

            case WEST:
                buffer.addVertex(m, x    , y, z + 1).setUv(u    , v    ).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z + 1).setUv(u    , v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z    ).setUv(u + w, v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x    , y, z    ).setUv(u + w, v    ).setColor(color.r, color.g, color.b, color.a);
                break;

            default:
        }
    }

    private void renderLightLevelCross(float x, float y, float z, Color4f color, float offset1, float offset2, float lineWidth, BufferBuilder buffer)
    {
        y += 0.005F;

        buffer.addVertex(x + offset1, y, z + offset1).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
        buffer.addVertex(x + offset2, y, z + offset2).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);

        buffer.addVertex(x + offset1, y, z + offset2).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
        buffer.addVertex(x + offset2, y, z + offset1).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
    }

    private void renderLightLevelSquare(float x, float y, float z, Color4f color, float offset1, float offset2, float lineWidth, BufferBuilder buffer)
    {
        y += 0.005F;

        buffer.addVertex(x + offset1, y, z + offset1).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
        buffer.addVertex(x + offset1, y, z + offset2).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);

        buffer.addVertex(x + offset1, y, z + offset2).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
        buffer.addVertex(x + offset2, y, z + offset2).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);

        buffer.addVertex(x + offset2, y, z + offset2).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
        buffer.addVertex(x + offset2, y, z + offset1).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);

        buffer.addVertex(x + offset2, y, z + offset1).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
        buffer.addVertex(x + offset1, y, z + offset1).setColor(color.r, color.g, color.b, color.a).setLineWidth(lineWidth);
    }

    private void updateLightLevels(Level world, BlockPos center)
    {
        this.ignoreRange = false;

        //System.out.printf("LL center %s\n", center.toShortString());

        final boolean aggressiveOpt = Configs.Generic.LIGHT_LEVEL_AGGRESSIVE_OPTIMIZATION.getBooleanValue();

        final int minX, minY, minZ, maxX, maxY, maxZ;
        BiPredicate<Integer, Integer> chunkPredicate = (_, _) -> true;
        BiFunction<Integer, Integer, Integer> additionalMinY = (_, _) -> Integer.MIN_VALUE;
        BiFunction<Integer, Integer, Integer> additionalMaxY = (_, _) -> Integer.MAX_VALUE;
        // decides if posPredicate can be skipped.
        IChunkLayerPredicate fullPredicate = (_, _, _) -> true;
        // expects getting called after chunkPredicate.
        Predicate<BlockPos> posPredicate = Predicates.alwaysTrue();
        configure:
        {
            final boolean ssFull = Configs.Generic.LIGHT_LEVEL_SPAWN_SPHERE_FULL.getBooleanValue();
            final boolean ssLimit = Configs.Generic.LIGHT_LEVEL_SPAWN_SPHERE_LIMIT.getBooleanValue();

            if (ssFull || ssLimit)
            {
                List<ShapeSpawnSphere> spawnSpheres = ShapeManager.INSTANCE.getAllShapes().stream()
                        .filter(s -> s instanceof ShapeSpawnSphere && s.isEnabled())
                        .map(s -> (ShapeSpawnSphere) s)
                        .filter(s -> s.contains(center.getX(), center.getY(), center.getZ()))
                        .sorted(Comparator.<ShapeSpawnSphere>comparingDouble(s -> s.getMargin() + s.getRadius()).reversed())
                        .toList();

                if (spawnSpheres.equals(lastSpawnSpheres) == false)
                {
                    this.lightInfos.clear();
                    lastSpawnSpheres = spawnSpheres;
                }
                if (spawnSpheres.isEmpty() == false)
                {
                    fullPredicate = (_, _, _) -> false;

                    if (ssFull)
                    {
                        this.ignoreRange = true;
                    }

                    if (spawnSpheres.size() == 1)
                    {
                        ShapeSpawnSphere ss = spawnSpheres.getFirst();
                        chunkPredicate = ss::mayIntersectWithChunk;
                        additionalMinY = ss::getChunkIntersectMinY;
                        additionalMaxY = ss::getChunkIntersectMaxY;
                        if (aggressiveOpt)
                        {
                            fullPredicate = ss::containsChunkLayer;
                        }
                        posPredicate = pos -> ss.contains(pos.getX(), pos.getY(), pos.getZ());
                    }
                    else
                    {
                        List<ShapeSpawnSphere> filteredSpawnSpheres = new ArrayList<>();
                        chunkPredicate = (cx, cz) -> {
                            filteredSpawnSpheres.clear();
                            filteredSpawnSpheres.addAll(spawnSpheres.stream()
                                    .filter(s -> s.mayIntersectWithChunk(cx, cz)).toList());
                            return filteredSpawnSpheres.isEmpty() == false;
                        };
                        if (aggressiveOpt)
                        {
                            fullPredicate = (cx, cz, y) -> filteredSpawnSpheres.stream()
                                    .anyMatch(s -> s.containsChunkLayer(cx, cz, y));
                        }
                        posPredicate = pos -> filteredSpawnSpheres.stream()
                                .anyMatch(s -> s.contains(pos.getX(), pos.getY(), pos.getZ()));
                        additionalMinY = (cx, cz) -> spawnSpheres.stream()
                                .mapToInt(s -> s.getChunkIntersectMinY(cx, cz)).min().getAsInt();
                        additionalMaxY = (cx, cz) -> spawnSpheres.stream()
                                .mapToInt(s -> s.getChunkIntersectMaxY(cx, cz)).min().getAsInt();
                    }

                    if (ssFull)
                    {
                        minX = spawnSpheres.stream().mapToInt(s -> (int) Math.floor(s.getEffectiveCenter().x - s.getMargin() - s.getRadius())).min().getAsInt();
                        minY = spawnSpheres.stream().mapToInt(s -> (int) Math.floor(s.getEffectiveCenter().y                 - s.getRadius())).min().getAsInt();
                        minZ = spawnSpheres.stream().mapToInt(s -> (int) Math.floor(s.getEffectiveCenter().z - s.getMargin() - s.getRadius())).min().getAsInt();
                        maxX = spawnSpheres.stream().mapToInt(s -> (int) Math.ceil(s.getEffectiveCenter().x + s.getMargin() + s.getRadius())).max().getAsInt();
                        maxY = spawnSpheres.stream().mapToInt(s -> (int) Math.ceil(s.getEffectiveCenter().y                 + s.getRadius())).max().getAsInt();
                        maxZ = spawnSpheres.stream().mapToInt(s -> (int) Math.ceil(s.getEffectiveCenter().z + s.getMargin() + s.getRadius())).max().getAsInt();
                        break configure;
                    }
                }
            }

            // default
            int radius = Configs.Generic.LIGHT_LEVEL_RANGE.getIntegerValue();
            minX = (center.getX() - radius) & ~15;
            minY = Integer.MIN_VALUE;
            minZ = (center.getZ() - radius) & ~15;
            maxX = (center.getX() + radius) | 15;
            maxY = Integer.MAX_VALUE;
            maxZ = (center.getZ() + radius) | 15;
        }

        final int minCX = (minX >> 4);
        final int minCZ = (minZ >> 4);
        final int maxCX = (maxX >> 4);
        final int maxCZ = (maxZ >> 4);
        LevelLightEngine lightingProvider = world.getChunkSource().getLightEngine();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        final int worldTopHeight = world.getMaxY() + 1;
        final boolean collisionCheck = Configs.Generic.LIGHT_LEVEL_COLLISION_CHECK.getBooleanValue();
        final boolean underWater = Configs.Generic.LIGHT_LEVEL_UNDER_WATER.getBooleanValue();
        final boolean autoHeight = Configs.Generic.LIGHT_LEVEL_AUTO_HEIGHT.getBooleanValue();
        final boolean skipBlockCheck = Configs.Generic.LIGHT_LEVEL_SKIP_BLOCK_CHECK.getBooleanValue();

        this.underWater = underWater;

        BlockState lastSkip = null;

        for (int cx = minCX; cx <= maxCX; ++cx)
        {
            for (int cz = minCZ; cz <= maxCZ; ++cz)
            {
                if (this.lightInfos.contains(cx, cz) || chunkPredicate.test(cx, cz) == false)
                {
                    continue;
                }

                LevelChunk chunk = world.getChunk(cx, cz);
                if (chunk.isEmpty())
                {
                    continue;
                }

                final int startX = Math.max( cx << 4      , minX);
                final int endX   = Math.min((cx << 4) + 15, maxX);
                final int startZ = Math.max( cz << 4      , minZ);
                final int endZ   = Math.min((cz << 4) + 15, maxZ);
                final int startY = Math.max(Math.max(minY, world.getMinY()), additionalMinY.apply(cx, cz));
                final int endY = Math.min(Math.min(maxY, WorldUtils.getHighestSectionYOffset(chunk) + 15 + 1), additionalMaxY.apply(cx, cz));
                int checkedSectionY = 1; // anything but multiply of 16

                LightLevelChunkInfo info = new LightLevelChunkInfo(cx, cz, world.getMinY());
                lightInfos.put(cx, cz, info);

                for (int y = startY; y <= endY; ++y)
                {
                    if (y > startY)
                    {
                        // If there are no blocks in the section below this layer, then we can skip it
                        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y - 1));

                        if (section.hasOnlyAir())
                        {
                            //y += 16 - (y & 0xF);
                            // jump to the next section if y - 1 is the same section.
                            // essentially Math.ceil(y / 16.0) * 16.
                            // the code above fails when y & 0xF == 0.
                            y = ((y - 1) | 0xF) + 1;
                            continue;
                        }
                    }

                    if (aggressiveOpt && checkedSectionY != (y & ~0xF))
                    {
                        checkedSectionY = y & ~0xF;
                        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                        // fake pos, may cause inaccurate result.
                        mutablePos.set(cx << 4, checkedSectionY, cz << 4);
                        // If there are all conductors (blocks spawning), then we can skip it
                        boolean hasNonConductor = section.getStates().maybeHas(s -> s.isRedstoneConductor(world, mutablePos) == false);
                        if (hasNonConductor == false)
                        {
                            y |= 0xF;
                            continue;
                        }
                        // according to spark profiler, analyzing palette and directly iterate IntList
                        // might optimize another 25%. but it needs mixin to get raw palette and IntList,
                        // and sounds difficult to maintain.
                    }

                    boolean partial = fullPredicate.test(cx, cz, y) == false;

                    for (int z = startZ; z <= endZ; ++z)
                    {
                        for (int x = startX; x <= endX; ++x)
                        {
                            mutablePos.set(x, y, z);
                            if (partial && posPredicate.test(mutablePos) == false)
                            {
                                continue;
                            }

                            BlockState state = chunk.getBlockState(mutablePos);
                            if (aggressiveOpt)
                            {
                                // compares with state at another pos, may cause inaccurate result.
                                if (lastSkip == state)
                                {
                                    continue;
                                }

                                if (state.isRedstoneConductor(world, mutablePos))
                                {
                                    lastSkip = state;
                                    continue;
                                }
                            }

                            if (this.canSpawnAtWrapper(x, y, z, chunk, world, skipBlockCheck) == false)
                            {
                                continue;
                            }

                            if ((collisionCheck == false || state.getCollisionShape(chunk, mutablePos).isEmpty()) &&
                                (underWater || state.getFluidState().isEmpty()))
                            {
                                int block = y < worldTopHeight ? lightingProvider.getLayerListener(LightLayer.BLOCK).getLightValue(mutablePos) : 0;
                                int sky   = y < worldTopHeight ? lightingProvider.getLayerListener(LightLayer.SKY).getLightValue(mutablePos) : 15;
                                double topY = autoHeight ? state.getShape(chunk, mutablePos).max(Direction.Axis.Y) : 0;

                                // Don't render the light level marker if it would be raised all the way to the next block space
                                if (topY < 1)
                                {
                                    info.push(x, y, z, (float) Math.max(0, topY), block, sky);
                                }
                            }
                        }
                    }
                }

                info.seal();
            }
        }
    }

    private boolean canSpawnAtWrapper(int x, int y, int z, ChunkAccess chunk, Level world, boolean skipBlockCheck)
    {
        try
        {
            return this.canSpawnAt(x, y, z, chunk, world, skipBlockCheck);
        }
        catch (Exception e)
        {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, 8000, "This dimension seems to have missing block tag data, the light level will not use the normal block spawnability checks in this dimension. This is known to happen on some Waterfall/BungeeCord/ViaVersion/whatever setups that have an older MC version at the back end.");
            this.tagsBroken = true;

            return false;
        }
    }

    /**
     * This method mimics the one from WorldEntitySpawner, but takes in the Chunk to avoid that lookup
     */
    private boolean canSpawnAt(int x, int y, int z, ChunkAccess chunk, Level world, boolean skipBlockCheck)
    {
        this.mutablePos.set(x, y - 1, z);
        BlockState stateDown = chunk.getBlockState(this.mutablePos);

        if ((skipBlockCheck && stateDown.isAir() == false && (stateDown.getBlock() instanceof LiquidBlock) == false) ||
            stateDown.isValidSpawn(world, this.mutablePos, EntityTypes.CREEPER))
        {
            this.mutablePos.set(x, y, z);
            BlockState state = chunk.getBlockState(this.mutablePos);

            if (this.isClearForSpawnWrapper(world, this.mutablePos, state, state.getFluidState(), EntityTypes.WITHER_SKELETON))
            {
                this.mutablePos.set(x, y + 1, z);
                BlockState stateUp1 = chunk.getBlockState(this.mutablePos);

                return this.isClearForSpawnWrapper(world, this.mutablePos, stateUp1, state.getFluidState(), EntityTypes.WITHER_SKELETON);
            }

            if (this.underWater && state.getFluidState().is(FluidTags.WATER))
            {
                this.mutablePos.set(x, y + 1, z);
                BlockState stateUp1 = chunk.getBlockState(this.mutablePos);

                return stateUp1.getFluidState().is(FluidTags.WATER) &&
                       chunk.getBlockState(this.mutablePos.set(x, y + 2, z)).isRedstoneConductor(world, this.mutablePos) == false;
            }
        }

        return false;
    }

    public boolean isClearForSpawnWrapper(BlockGetter blockView, BlockPos pos, BlockState state, FluidState fluidState, EntityType<?> entityType)
    {
        return this.tagsBroken ? isClearForSpawnStripped(blockView, pos, state, fluidState, entityType) : NaturalSpawner.isValidEmptySpawnBlock(blockView, pos, state, fluidState, entityType);
    }

    /**
     * This method is basically a copy of SpawnHelper.isClearForSpawn(), except that
     * it removes any calls to BlockState.isIn(), which causes an exception on certain
     * ViaVersion servers that have old 1.12.2 worlds.
     * (or possibly newer versions as well, but older than 1.16 or 1.15 or whenever the tag syncing was added)
     */
    public static boolean isClearForSpawnStripped(BlockGetter blockView, BlockPos pos, BlockState state, FluidState fluidState, EntityType<?> entityType)
    {
        if (state.isCollisionShapeFullBlock(blockView, pos) || state.isSignalSource() || fluidState.isEmpty() == false)
        {
            return false;
        }
        else if (state.is(BlockTags.INVALID_SPAWN_INSIDE))
        {
            return false;
        }

        // this also calls BlockState isIn()
        return entityType.isBlockDangerous(state) == false;
    }

    // eliminates ChunkPos instantiation
    private static class ChunkLightInfoMap extends Long2ObjectOpenHashMap<LightLevelChunkInfo>
    {
        private static final long MASK32 = ((1L << 32L) - 1L);

        public void put(int chunkX, int chunkZ, LightLevelChunkInfo info)
        {
            long key = ((long) chunkX << 32L) | ((long) chunkZ & MASK32);
            super.put(key, info);
        }

        public boolean contains(int chunkX, int chunkZ)
        {
            long key = ((long) chunkX << 32L) | ((long) chunkZ & MASK32);
            return super.containsKey(key);
        }

        public void removeNeighbors(int chunkX, int chunkZ)
        {
            long keyLeft = (long) chunkX << 32L;
            long key0 = keyLeft | ((long) (chunkZ - 1) & MASK32);
            long key1 = keyLeft | ((long) (chunkZ    ) & MASK32);
            long key2 = keyLeft | ((long) (chunkZ + 1) & MASK32);
            super.remove(key0);
            super.remove(key1);
            super.remove(key2);
            super.remove(key0 - (1L << 32L));
            super.remove(key1 - (1L << 32L));
            super.remove(key2 - (1L << 32L));
            super.remove(key0 + (1L << 32L));
            super.remove(key1 + (1L << 32L));
            super.remove(key2 + (1L << 32L));
        }
    }

    private class Timer
    {
        private int update, render, end;
        private final short[] history = new short[16];
        private int index = 0;
        private int dist;

        public void timeUpdate()
        {
            this.update = (int) (System.nanoTime() / 1000L);
        }

        public void timeRender()
        {
            this.render = (int) (System.nanoTime() / 1000L);
        }

        public void timeEnd()
        {
            this.end = (int) (System.nanoTime() / 1000L);
            short score = (short) Math.clamp(this.end - this.render - 5000, 0, 20000);
            this.history[this.index] = score;
            if (++this.index >= 16)
            {
                this.index = 0;
            }
            this.dist = 0;
        }

        public int getMinDist()
        {
            if (this.dist > 0)
            {
                return this.dist;
            }

            int sum = 0;
            for (int i = 0; i < 16; ++i)
            {
                sum += this.history[i];
            }

            this.dist = 1 + sum / 16 / 2500;
            return this.dist;
        }

        @SuppressWarnings("unused")
        public void debugLog()
        {
            System.out.printf("LL markers: %d, update time: %.3f ms, render time: %.3f ms, min dist: %d\n",
                    OverlayRendererLightLevel.this.lightInfos.values().stream()
                            .mapToInt(LightLevelChunkInfo::size).sum(),
                    (double) (this.render - this.update) / 1000,
                    (double) (this.end - this.render) / 1000,
                    this.getMinDist()
            );
        }
    }

    private interface IMarkerRenderer
    {
        void render(float x, float y, float z, Color4f color, float offset1, float offset2, float lineWidth, BufferBuilder buffer);
    }

    private interface IChunkLayerPredicate
    {
        boolean test(int chunkX, int chunkZ, int y);
    }
}
