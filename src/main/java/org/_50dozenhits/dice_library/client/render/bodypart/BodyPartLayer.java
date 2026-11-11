package org._50dozenhits.dice_library.client.render.bodypart;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.DiceClientConfig;
import org._50dozenhits.dice_library.client.render.bodypart.BodyParts.Look;
import org._50dozenhits.dice_library.client.render.bodypart.BodyParts.Worn;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;
import software.bernie.geckolib.util.RenderUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Draws the body parts a player wears (BodyParts.worn) on their head or back, over armor, with GeckoLib, each in its
 * look: the color's texture, then the markings over it, then a glow for rare looks.
 * Each player gets their own animation state per part: a loop (or the one the part's state function picks), a flinch
 * when hurt, and a fidget now and then. Hidden while invisible, and by the bodyParts.show client setting.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BodyPartLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final Renderer RENDERER = new Renderer();
    // per player, per part. Weak so players that are gone take their animations with them
    private static final Map<Player, Map<ResourceLocation, Part>> PARTS = new WeakHashMap<>();

    private BodyPartLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @SubscribeEvent
    static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            if (event.getSkin(skin) instanceof PlayerRenderer renderer) renderer.addLayer(new BodyPartLayer(renderer));
        }
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float headYaw, float headPitch) {
        if (player.isInvisible() || !shows(player)) return;
        List<Worn> worn = BodyParts.worn(player);
        if (worn.isEmpty()) return;
        Map<ResourceLocation, Part> parts = PARTS.computeIfAbsent(player, p -> new HashMap<>());
        for (Worn one : worn) {
            BodyPart bodyPart = BodyParts.part(one.use().part());
            if (bodyPart == null) continue;
            Part part = parts.computeIfAbsent(one.use().part(), id -> new Part(bodyPart));
            part.update(player, one);
            ModelPart attach = bodyPart.attach == BodyPart.Attach.HEAD ? getParentModel().head : getParentModel().body;
            pose.pushPose();
            attach.translateAndRotate(pose);
            // the model part's space has y down. The Blockbench models stand on a player whose neck is at y 24 pixels
            pose.mulPose(Axis.ZP.rotationDegrees(180));
            pose.translate(0, -1.5, 0);
            // a rolled size scales around the part's anchor
            float ay = bodyPart.anchorY / 16, az = bodyPart.anchorZ / 16;
            pose.translate(0, ay, az);
            pose.scale(part.scale, part.scale, part.scale);
            pose.translate(0, -ay, -az);
            // GeoObjectRenderer centres an object in a block. Undo that
            pose.translate(-0.5, -0.51, -0.5);
            // One clock for all three passes. GeckoLib only skips re-posing the bones when the time hasn't moved, and
            // its own clock is real time, so without this every pass is posed a hair apart mid-animation and the
            // markings and glow fight the color underneath.
            part.frameTick = RenderUtil.getCurrentTick();
            for (Pass pass : Pass.values()) {
                if (part.texture(pass) == null) continue;
                part.pass = pass;
                RENDERER.render(pose, part, buffers, null, null, light, partialTick);
            }
            part.pass = Pass.COLOR;
            pose.popPose();
        }
    }

    private static boolean shows(Player player) {
        return switch (DiceClientConfig.bodyParts) {
            case ALL -> true;
            case OTHERS -> player != Minecraft.getInstance().player;
            case NONE -> false;
        };
    }

    // The draws of one part: its color, its markings over that, then the glow of a rare color or rare markings.
    enum Pass {
        COLOR,
        MARKINGS,
        GLOW
    }

    // One player's copy of one part: its look this frame and its animation state. A shape with its own animations
    // (BodyPart.Builder.shapeAnimations) plays <name>_<shape> in place of every <name>.
    static final class Part implements GeoAnimatable {
        private static final Map<String, RawAnimation> LOOPS = new HashMap<>();
        private static final Map<String, RawAnimation> ONCE = new HashMap<>();

        final BodyPart part;
        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
        Player player;
        int count;
        String shape = "";
        float scale = 1;
        Pass pass = Pass.COLOR;
        // the animation time of this frame's passes
        double frameTick = -1;
        private ResourceLocation color;
        @Nullable
        private ResourceLocation markings;
        private boolean markingsGlow;
        @Nullable
        private ResourceLocation glow;
        // the hurt flinch starts when hurtTime jumps up
        private int lastHurtTime;
        private boolean flinching;
        private long nextFidget = -1;
        @Nullable
        private RawAnimation fidget;

        Part(BodyPart part) {
            this.part = part;
        }

        private boolean shapeAnimated() {
            return part.shapeAnimations.containsKey(shape);
        }

        // the animation by that name, or its shape's own version
        private RawAnimation loop(String name) {
            String full = shapeAnimated() ? name + "_" + shape : name;
            return LOOPS.computeIfAbsent(full, n -> RawAnimation.begin().thenLoop(n));
        }

        private RawAnimation once(String name) {
            return ONCE.computeIfAbsent(name, n -> RawAnimation.begin().thenPlay(n));
        }

        void update(Player player, Worn worn) {
            Look look = worn.look();
            this.player = player;
            this.count = worn.use().count();
            this.shape = look.shape();
            this.scale = look.scale();
            this.color = part.texture(look.color());
            this.glow = look.rareColor() ? part.texture(look.color() + "_glow") : null;
            this.markings = look.markings().equals("none") ? null : part.texture("mark_" + look.markings());
            this.markingsGlow = look.rareMarkings();
        }

        @Nullable
        ResourceLocation texture(Pass pass) {
            return switch (pass) {
                case COLOR -> color;
                case MARKINGS -> markingsGlow ? null : markings;
                case GLOW -> glow != null ? glow : markingsGlow ? markings : null;
            };
        }

        RenderType renderType() {
            ResourceLocation texture = texture(pass);
            return switch (pass) {
                case COLOR -> RenderType.entityCutoutNoCull(texture);
                case MARKINGS -> OverlayTypes.MARKINGS.apply(texture);
                case GLOW -> OverlayTypes.GLOW.apply(texture);
            };
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            controllers.add(new AnimationController<>(this, "main", 6, this::animate));
            controllers.add(new AnimationController<>(this, "fidget", 4, this::fidget));
            controllers.add(new AnimationController<>(this, "react", 2, this::react));
        }

        private PlayState animate(AnimationState<Part> state) {
            return state.setAndContinue(loop(part.loopNow(player)));
        }

        // every 8 to 20 seconds, one of the part's fidgets, but not mid-glide or mid-flinch
        private PlayState fidget(AnimationState<Part> state) {
            List<String> fidgets = shapeAnimated() ? part.shapeAnimations.get(shape) : part.fidgets;
            if (player == null || fidgets.isEmpty()) return PlayState.STOP;
            long now = player.tickCount;
            if (fidget != null) {
                if (!state.getController().hasAnimationFinished()) return PlayState.CONTINUE;
                fidget = null;
                nextFidget = now + 160 + player.getRandom().nextInt(240);
                return PlayState.STOP;
            }
            if (nextFidget < 0) nextFidget = now + 100 + player.getRandom().nextInt(300);
            boolean busy = flinching || !part.loopNow(player).equals(part.loop);
            if (now < nextFidget || busy) return PlayState.STOP;
            fidget = once(fidgets.get(player.getRandom().nextInt(fidgets.size())));
            state.getController().forceAnimationReset();
            return state.setAndContinue(fidget);
        }

        private PlayState react(AnimationState<Part> state) {
            if (player == null) return PlayState.STOP;
            int hurt = player.hurtTime;
            if (hurt > lastHurtTime) {
                flinching = true;
                state.getController().forceAnimationReset();
            }
            lastHurtTime = hurt;
            if (flinching && state.getController().hasAnimationFinished() && hurt == 0) flinching = false;
            return flinching ? state.setAndContinue(once(shapeAnimated() ? part.hit + "_" + shape : part.hit)) : PlayState.STOP;
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            return cache;
        }

        @Override
        public double getTick(Object object) {
            return frameTick >= 0 ? frameTick : RenderUtil.getCurrentTick();
        }
    }

    // One model for every part. The files come from the part and its look, and the shape bones and the part's BoneHook
    // are set per draw since every player shares the baked model.
    static final class Model extends GeoModel<Part> {
        @Override
        public ResourceLocation getModelResource(Part part) {
            return part.part.geo;
        }

        @Override
        public ResourceLocation getTextureResource(Part part) {
            ResourceLocation texture = part.texture(part.pass);
            return texture != null ? texture : part.texture(Pass.COLOR);
        }

        @Override
        public ResourceLocation getAnimationResource(Part part) {
            return part.part.animations;
        }

        @Override
        public RenderType getRenderType(Part part, ResourceLocation texture) {
            return part.renderType();
        }

        @Override
        public void setCustomAnimations(Part part, long instanceId, AnimationState<Part> state) {
            String mine = "s_" + part.shape;
            for (GeoBone bone : getAnimationProcessor().getRegisteredBones()) {
                String name = bone.getName();
                if (name.startsWith("s_")) bone.setHidden(!(name.equals(mine) || name.startsWith(mine + "_")));
            }
            if (part.part.bones != null && part.player != null) part.part.bones.pose(this::getBone, part.count, part.player);
        }
    }

    // The markings and glow draw over the color on the same faces. Neither culls, so a flat wing or ear that turns its
    // back still shows them, and both are nudged toward the camera so they never fight the color for depth. The glow
    // is additive and full bright, like vanilla's glowing eyes.
    static final class OverlayTypes extends RenderType {
        static final Function<ResourceLocation, RenderType> MARKINGS = Util.memoize(texture -> create("dicelib_body_part_markings",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, true, true, CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(true)));
        static final Function<ResourceLocation, RenderType> GLOW = Util.memoize(texture -> create("dicelib_body_part_glow",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, true, CompositeState.builder()
                        .setShaderState(RENDERTYPE_EYES_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(ADDITIVE_TRANSPARENCY)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(false)));

        private OverlayTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort,
                             Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }
    }

    static final class Renderer extends GeoObjectRenderer<Part> {
        Renderer() {
            super(new Model());
        }
    }
}
