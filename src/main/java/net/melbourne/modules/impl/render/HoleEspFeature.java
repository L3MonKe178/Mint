package net.melbourne.modules.impl.render;

import net.melbourne.services.Services;
import net.melbourne.events.SubscribeEvent;
import net.melbourne.events.impl.RenderWorldEvent;
import net.melbourne.events.impl.TickEvent;
import net.melbourne.modules.Category;
import net.melbourne.modules.Feature;
import net.melbourne.modules.FeatureInfo;
import net.melbourne.settings.types.BooleanSetting;
import net.melbourne.settings.types.ColorSetting;
import net.melbourne.settings.types.ModeSetting;
import net.melbourne.settings.types.NumberSetting;
import net.melbourne.settings.types.WhitelistSetting;
import net.melbourne.utils.animations.Easing;
import net.melbourne.utils.block.hole.HoleUtils;
import net.melbourne.utils.graphics.impl.Renderer3D;
import net.melbourne.utils.miscellaneous.ColorUtils;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@FeatureInfo(name = "HoleEsp", category = Category.Render)
public class HoleEspFeature extends Feature {

    public NumberSetting range = new NumberSetting("Range", "Max render range", 10, 1, 50);
    public final WhitelistSetting holes = new WhitelistSetting("Holes", "Hole types to render", WhitelistSetting.Type.CUSTOM, new String[]{}, new String[]{"Single", "Double", "Quad", "Incomplete"});

    public ModeSetting incompletes = new ModeSetting("Incompletes", "Broken hole logic", "Normal", new String[]{"None", "Normal", "Sinwave"},
            () -> holes.getWhitelistIds().contains("Incomplete"));

    public NumberSetting waveSpeed = new NumberSetting("Speed", "Speed of Sinwave (ms)", 500, 100, 2000,
            () -> holes.getWhitelistIds().contains("Incomplete") && incompletes.getValue().equals("Sinwave"));

    public ModeSetting fill = new ModeSetting("Fill", "Fill mode", "Normal", new String[]{"None", "Normal", "Gradient"});
    public ModeSetting outline = new ModeSetting("Outline", "Outline mode", "Normal", new String[]{"None", "Normal", "Gradient", "Cross"});
    public NumberSetting height = new NumberSetting("Height", "ESP height", 1.0, -2.0, 2.0);
    public BooleanSetting fade = new BooleanSetting("Fade", "Distance fading", false);

    public ColorSetting bedrockColor = new ColorSetting("BedrockColor", "Bedrock color", new Color(255, 0, 0));
    public ColorSetting obsidianColor = new ColorSetting("ObsidianColor", "Obsidian color", new Color(0, 255, 0));
    public ColorSetting mixedColor = new ColorSetting("MixedColor", "Mixed color", new Color(0, 0, 255));
    public ColorSetting incompleteColor = new ColorSetting("IncompleteColor", "Incomplete color", new Color(255, 150, 0),
            () -> holes.getWhitelistIds().contains("Incomplete"));

    private final Map<BlockPos, HoleInfo> holeMap = new HashMap<>();

    private static class HoleInfo {
        HoleUtils.Hole hole;
        Color currentColor;

        HoleInfo(HoleUtils.Hole hole, Color startColor) {
            this.hole = hole;
            this.currentColor = startColor;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.world == null) return;

        List<BlockPos> sphere = new ArrayList<>();
        for (int i = 0; i < Services.WORLD.getRadius(range.getValue().doubleValue()); i++) {
            sphere.add(mc.player.getBlockPos().add(Services.WORLD.getOffset(i)));
        }

        Map<BlockPos, HoleUtils.Hole> found = new HashMap<>();
        for (BlockPos pos : sphere) {
            HoleUtils.Hole h = null;
            if (holes.getWhitelistIds().contains("Incomplete") && HoleUtils.isIncompleteHole(pos) && !incompletes.getValue().equals("None"))
                h = new HoleUtils.Hole(new Box(pos), HoleUtils.HoleType.INCOMPLETE, HoleUtils.HoleSafety.OBSIDIAN);
            else if (holes.getWhitelistIds().contains("Quad") && (h = HoleUtils.getQuadHole(pos, 0)) != null);
            else if (holes.getWhitelistIds().contains("Single") && (h = HoleUtils.getSingleHole(pos, 0)) != null);
            else if (holes.getWhitelistIds().contains("Double") && (h = HoleUtils.getDoubleHole(pos, 0)) != null);

            if (h != null) found.put(pos, h);
        }

        synchronized (holeMap) {
            holeMap.keySet().removeIf(pos -> !found.containsKey(pos));
            for (Map.Entry<BlockPos, HoleUtils.Hole> entry : found.entrySet()) {
                Color target = getRawColor(entry.getValue());
                if (!holeMap.containsKey(entry.getKey())) {
                    holeMap.put(entry.getKey(), new HoleInfo(entry.getValue(), target));
                } else {
                    HoleInfo info = holeMap.get(entry.getKey());
                    info.hole = entry.getValue();
                    info.currentColor = lerpColor(info.currentColor, target, 0.15f);
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.world == null || mc.player == null) return;

        float sinwave = (float) ((Math.sin(System.currentTimeMillis() / waveSpeed.getValue().doubleValue() * Math.PI) + 1.0) / 2.0);

        synchronized (holeMap) {
            if (holeMap.isEmpty()) return;
            for (HoleInfo info : holeMap.values()) {
                HoleUtils.Hole hole = info.hole;
                Box box = new Box(hole.box().minX, hole.box().minY, hole.box().minZ,
                        hole.box().maxX, hole.box().minY + height.getValue().doubleValue(), hole.box().maxZ);

                float pulse = (hole.type() == HoleUtils.HoleType.INCOMPLETE && incompletes.getValue().equals("Sinwave")) ? sinwave : 1.0f;

                float easing = fade.getValue() ? getEasing(hole) : 1f;
                Color fillColor = ColorUtils.getColor(info.currentColor, (int) (60 * pulse * easing));
                Color lineColor = ColorUtils.getColor(info.currentColor, (int) (255 * pulse * easing));

                if (!fill.getValue().equalsIgnoreCase("None"))
                    Renderer3D.renderBox(event.getContext(), box, fill.getValue().equals("Gradient") ? new Color(0,0,0,0) : fillColor, fillColor);

                if (!outline.getValue().equalsIgnoreCase("None")) {
                    if (outline.getValue().equals("Cross")) Renderer3D.renderBoxCross(event.getContext(), box, lineColor, lineColor);
                    else Renderer3D.renderBoxOutline(event.getContext(), box, outline.getValue().equals("Gradient") ? new Color(0,0,0,0) : lineColor, lineColor);
                }
            }
        }
    }

    private Color getRawColor(HoleUtils.Hole hole) {
        if (hole.type() == HoleUtils.HoleType.INCOMPLETE) return incompleteColor.getColor();
        return switch (hole.safety()) {
            case BEDROCK -> bedrockColor.getColor();
            case MIXED -> mixedColor.getColor();
            default -> obsidianColor.getColor();
        };
    }

    private Color lerpColor(Color a, Color b, float t) {
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t)
        );
    }

    private float getEasing(HoleUtils.Hole hole) {
        float dist = (float) Math.sqrt(mc.player.squaredDistanceTo(hole.box().getCenter()));
        return Easing.ease(1.0f - MathHelper.clamp(dist / range.getValue().floatValue(), 0.0f, 1.0f), Easing.Method.LINEAR);
    }
}