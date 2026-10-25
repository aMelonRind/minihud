package fi.dy.masa.minihud.renderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import com.google.common.base.Predicates;
import fi.dy.masa.minihud.renderer.shapes.ShapeManager;
import fi.dy.masa.minihud.renderer.shapes.ShapeSpawnSphere;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
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

import javax.annotation.Nullable;

public class OverlayRendererLightLevel extends OverlayRendererBase
{
    public static final OverlayRendererLightLevel INSTANCE = new OverlayRendererLightLevel();
    private static final Identifier TEXTURE_NUMBERS = Identifier.fromNamespaceAndPath(Reference.MOD_ID, "textures/misc/light_level_numbers.png");

    private final List<LightLevelInfo> lightInfos;
    private BlockPos.MutableBlockPos mutablePos;
    private Direction lastDirection;
    @Nullable private List<ShapeSpawnSphere> lastInShapes;
    @Nullable private List<ShapeSpawnSphere> lastOutShapes;

    private boolean tagsBroken;
    private boolean needsUpdate;
    private boolean hasData;

    // if implemented as method param, it'll break tweakermore.
    private boolean underWater;

    protected OverlayRendererLightLevel()
    {
        this.lightInfos = new ArrayList<>();
        this.mutablePos = new BlockPos.MutableBlockPos();
        this.lastDirection = Direction.NORTH;
        this.hasData = false;
        this.lastInShapes = null;
        this.lastOutShapes = null;
    }

    @Override
    public String getName()
    {
        return "LightLevel";
    }

    public void setNeedsUpdate()
    {
        this.needsUpdate = true;
        // Clean buffers when receiving the RenderCallback.
        this.clearBuffers();
    }

    public void onSpawnSphereUpdate()
    {
        boolean hasShapeInteraction = Configs.Generic.LIGHT_LEVEL_SPAWN_SPHERE_FULL.getBooleanValue() ||
                Configs.Generic.LIGHT_LEVEL_SPAWN_SPHERE_LIMIT.getBooleanValue();

        if (hasShapeInteraction)
        {
            OverlayRendererLightLevel.INSTANCE.setNeedsUpdate();
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
        return this.needsUpdate || this.detectPlayerMove(entity) ||
                (Configs.Generic.LIGHT_LEVEL_NUMBER_ROTATION.getBooleanValue() &&
                        Configs.Generic.LIGHT_LEVEL_NUMBER_CONDITION.getOptionListValue() != LightLevelRenderCondition.NEVER &&
                        this.lastDirection != entity.getDirection());
    }

    private boolean detectPlayerMove(Entity entity)
    {
        if (this.lastUpdatePos == null)
        {
            return true;
        }

        boolean movedRange = Math.abs(entity.getX() - this.lastUpdatePos.getX()) > 4 ||
                Math.abs(entity.getY() - this.lastUpdatePos.getY()) > 4 ||
                Math.abs(entity.getZ() - this.lastUpdatePos.getZ()) > 4;
        if (movedRange == false)
        {
            return false;
        }

        if (this.lastInShapes == null)
        {
            return true;
        }

        BlockPos pos = PositionUtils.getEntityBlockPos(entity);
        boolean match = this.lastInShapes.stream().allMatch(s -> s.contains(pos.getX(), pos.getY(), pos.getZ())) &&
                (this.lastOutShapes == null || this.lastOutShapes.stream().allMatch(s -> s.contains(pos.getX(), pos.getY(), pos.getZ()) == false));
        if (match)
        {
            this.lastUpdatePos = pos;
            return false;
        }

        this.setNeedsUpdate();
        return true;
    }

    @Override
    public void update(Vec3d cameraPos, Entity entity, Minecraft mc, ProfilerFiller profiler)
    {
        if (mc.level == null)
        {
            this.needsUpdate = false;
            return;
        }

//        long pre = System.nanoTime();
        BlockPos pos = PositionUtils.getEntityBlockPos(entity);
        this.hasData = this.updateLightLevels(mc.level, pos);
        this.renderThrough = Configs.Generic.LIGHT_LEVEL_RENDER_THROUGH.getBooleanValue();

        if (this.hasData())
        {
            this.render(cameraPos, mc, profiler);
        }

//        System.out.printf("LL markers: %d, time: %.3f s\n", this.lightInfos.size(), (double) (System.nanoTime() - pre) / 1000000000D);

        this.lastUpdatePos = pos;
        this.lastDirection = entity.getDirection();
        this.needsUpdate = false;
    }

    @Override
    public boolean hasData()
    {
        return this.hasData && !this.lightInfos.isEmpty();
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

        profiler.push("light_level_quads");
        int safeThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_SAFE.getIntegerValue();
        int dimThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_DIM.getIntegerValue();
        Direction numberFacing = Configs.Generic.LIGHT_LEVEL_NUMBER_ROTATION.getBooleanValue() ? mc.player.getDirection() : Direction.NORTH;
        boolean useColoredNumbers = Configs.Generic.LIGHT_LEVEL_COLORED_NUMBERS.getBooleanValue();
        LightLevelNumberMode numberMode = (LightLevelNumberMode) Configs.Generic.LIGHT_LEVEL_NUMBER_MODE.getOptionListValue();

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

        profiler.push("light_level_outlines");
        int safeThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_SAFE.getIntegerValue();
        int dimThreshold = Configs.Generic.LIGHT_LEVEL_THRESHOLD_DIM.getIntegerValue();
        LightLevelMarkerMode markerMode = (LightLevelMarkerMode) Configs.Generic.LIGHT_LEVEL_MARKER_MODE.getOptionListValue();

        RenderObjectVbo ctx = this.renderObjects.get(1);
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
        this.hasData = false;
    }

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
        boolean autoHeight = Configs.Generic.LIGHT_LEVEL_AUTO_HEIGHT.getBooleanValue();
        Color4f color;

        for (LightLevelInfo info : this.lightInfos)
        {
            if (condition.shouldRender(info.block, dimThreshold, safeThreshold))
            {
                long pos = info.pos;
                double x = BlockPos.getX(pos) - offsetX;
                double y = (autoHeight ? info.y : BlockPos.getY(pos)) - offsetY;
                double z = BlockPos.getZ(pos) - offsetZ;

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
        boolean autoHeight = Configs.Generic.LIGHT_LEVEL_AUTO_HEIGHT.getBooleanValue();
        Color4f color;

        for (LightLevelInfo info : this.lightInfos)
        {
            if (condition.shouldRender(info.block, dimThreshold, safeThreshold))
            {
                long pos = info.pos;
                double x = BlockPos.getX(pos) - dx;
                double y = (autoHeight ? info.y : BlockPos.getY(pos)) - dy;
                double z = BlockPos.getZ(pos) - dz;
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
                buffer.addVertex(m, x, y, z).setUv(u    , v    ).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x, y, z + 1).setUv(u    , v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z + 1).setUv(u + w, v + w).setColor(color.r, color.g, color.b, color.a);
                buffer.addVertex(m, x + 1, y, z).setUv(u + w, v    ).setColor(color.r, color.g, color.b, color.a);
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

    private boolean updateLightLevels(Level world, BlockPos center)
    {
        this.lightInfos.clear();
        this.lastInShapes = null;
        this.lastOutShapes = null;

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
                List<ShapeSpawnSphere> allSpawnSpheres = ShapeManager.INSTANCE.getAllShapes().stream()
                        .filter(s -> s instanceof ShapeSpawnSphere && s.isEnabled())
                        .map(s -> (ShapeSpawnSphere) s)
                        .toList();
                List<ShapeSpawnSphere> spawnSpheres = allSpawnSpheres.stream()
                        .filter(s -> s.contains(center.getX(), center.getY(), center.getZ()))
                        .sorted(Comparator.<ShapeSpawnSphere>comparingDouble(s -> s.getMargin() + s.getRadius()).reversed())
                        .toList();

                if (spawnSpheres.isEmpty() == false)
                {
                    fullPredicate = (_, _, _) -> false;

                    if (ssFull)
                    {
                        this.lastInShapes = spawnSpheres;
                        this.lastOutShapes = allSpawnSpheres.stream()
                                .filter(s -> spawnSpheres.contains(s) == false)
                                .toList();
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
            minX = center.getX() - radius;
            minY = center.getY() - radius;
            minZ = center.getZ() - radius;
            maxX = center.getX() + radius;
            maxY = center.getY() + radius;
            maxZ = center.getZ() + radius;
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
            final int startX = Math.max( cx << 4      , minX);
            final int endX   = Math.min((cx << 4) + 15, maxX);

            for (int cz = minCZ; cz <= maxCZ; ++cz)
            {
                if (chunkPredicate.test(cx, cz) == false)
                {
                    continue;
                }

                LevelChunk chunk = world.getChunk(cx, cz);
                if (chunk.isEmpty())
                {
                    continue;
                }

                final int startZ = Math.max( cz << 4      , minZ);
                final int endZ   = Math.min((cz << 4) + 15, maxZ);
                final int startY = Math.max(Math.max(minY, world.getMinY()), additionalMinY.apply(cx, cz));
                final int endY = Math.min(Math.min(maxY, WorldUtils.getHighestSectionYOffset(chunk) + 15 + 1), additionalMaxY.apply(cx, cz));
                int checkedSectionY = 1; // anything but multiply of 16

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

                    boolean full = fullPredicate.test(cx, cz, y);

                    for (int x = startX; x <= endX; ++x)
                    {
                        for (int z = startZ; z <= endZ; ++z)
                        {
                            mutablePos.set(x, y, z);
                            if (full == false && posPredicate.test(mutablePos) == false)
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
                                double topY = state.getShape(chunk, mutablePos).max(Direction.Axis.Y);

                                // Don't render the light level marker if it would be raised all the way to the next block space
                                if (autoHeight == false || topY < 1)
                                {
                                    float posY = topY >= 0 ? y + (float) topY : y;
                                    this.lightInfos.add(new LightLevelInfo(mutablePos.asLong(), posY, block, sky));
                                    //y += 2; // if the spot is spawnable, that means the next spawnable spot can be the third block up
                                }
                            }
                        }
                    }
                }
            }
        }

        return this.lightInfos.isEmpty() == false;
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

    public static class LightLevelInfo
    {
        public long pos;
        public byte block;
        public byte sky;
        public float y;

        public LightLevelInfo(long pos, float y, int block, int sky)
        {
            this.pos = pos;
            this.y = y;
            this.block = (byte) block;
            this.sky = (byte) sky;
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
