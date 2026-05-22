package top.furryaxw.zstd_compresser;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class ZstdKeyMappings {

    public static final KeyMapping TOGGLE_HUD = new KeyMapping(
            "key.zstd_compresser.toggle_hud",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.zstd_compresser"
    );
}
