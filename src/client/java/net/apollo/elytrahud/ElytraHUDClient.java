package net.apollo.elytrahud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Pair;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static net.apollo.elytrahud.config.load_save.load;

public class ElytraHUDClient implements ClientModInitializer {
    //#################  CONFIG  #########################

    // HUD
    public static boolean showDurability = true;
    public static int position = 6;
    public static boolean HUD_show_percent = true;

    // message
    public static boolean showWarning = true;
    public static String messageText = "Elytra is about to break!";
    public static int message_color = 12451840;

    //####################################################
    private static final DecimalFormat df = new DecimalFormat("###.#");

    private String durability = "0 %";
    private boolean show = false;
    private final float minPerc = 0.046f;

    public static final String config_file_path = FabricLoader.getInstance().getConfigDir().toAbsolutePath() + "\\ElytraHUD_config.toml";

    public static final boolean IS_TRINKETS_API_PRESENT;
    private static Method getTrinketComponentMethod;
    private static Method getEquippedMethod;

    // Use Items.ELYTRA (stable across 1.21.x)
    public static final Predicate<ItemStack> ELYTRA_CHECK = stack -> stack.isOf(Items.ELYTRA);

    static {
        boolean isPresent;
        try {
            Class<?> trinketsApiClass = Class.forName("dev.emi.trinkets.api.TrinketsApi");
            Class<?> trinketComponentClass = Class.forName("dev.emi.trinkets.api.TrinketComponent");

            getTrinketComponentMethod = trinketsApiClass.getMethod("getTrinketComponent", LivingEntity.class);
            getEquippedMethod = trinketComponentClass.getMethod("getEquipped", Predicate.class);

            isPresent = true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            isPresent = false;
        }
        IS_TRINKETS_API_PRESENT = isPresent;
    }

    @Override
    public void onInitializeClient() {
        load();

        ClientTickEvents.START_CLIENT_TICK.register((MinecraftClient client) -> {
            ClientPlayerEntity player = client.player;
            if (player == null) return;

            boolean isCreative = player.isCreative();
            boolean hiddenHUD = client.options.hudHidden;

            if (isCreative || hiddenHUD) { show = false; return; }

            // Chest slot (no deprecated getArmorStack)
            ItemStack is = player.getEquippedStack(EquipmentSlot.CHEST);
            boolean hasElytra = is.isOf(Items.ELYTRA);

            if (!hasElytra && IS_TRINKETS_API_PRESENT) {
                is = getTrinketsElytraItemStack(player);
                hasElytra = is.isOf(Items.ELYTRA);
            }

            if (!hasElytra) { show = false; return; }

            int dam = is.getMaxDamage() - is.getDamage();
            float durability_float = (dam == 1) ? 0f : (float) dam / is.getMaxDamage();

			if (HUD_show_percent) {
				int pct = Math.round(durability_float * 100f);
				durability = pct + "%";
			} else {
				durability = dam + " / " + is.getMaxDamage();
			} // fixed your font issues too. enjoy.

            // Keep the warning without isFallFlying() (mapping shifted in 1.21.8)
            if (durability_float < minPerc && showWarning) {
                player.sendMessage(
                    Text.literal(messageText).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(message_color)).withBold(true)),
                    true
                );
            }

            show = true;
        });

		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			if (!show || !showDurability) return;

			MinecraftClient mc = MinecraftClient.getInstance();
			TextRenderer renderer = mc.textRenderer;

			int width  = context.getScaledWindowWidth();
			int height = context.getScaledWindowHeight();

			int renderX, renderY;
			final int upperY = 22, centerY = height / 2, lowerY = height - 3;
			final int leftX  = 5,  rightX  = width - 78;

			switch (position) {
				case 0, 2, 4 -> renderX = leftX;
				case 1, 3, 7 -> renderX = rightX;
				case 5 -> renderX = -172 + (width / 2);
				default -> renderX = 92 + (width / 2);
			}
			switch (position) {
				case 0, 1 -> renderY = upperY;
				case 2, 3 -> renderY = centerY;
				default -> renderY = lowerY;
			}

			// icon
			context.drawItem(new ItemStack(Items.ELYTRA), renderX + 5, renderY - 16);

			// number (opaque white, with shadow)
			context.drawTextWithShadow(renderer, durability, renderX + 23, renderY - 12, 0xFFFFFFFF);
		});
    }

    @SuppressWarnings("unchecked")
    public ItemStack getTrinketsElytraItemStack(LivingEntity livingEntity) {
        try {
            Optional<?> trinketComponentOpt = (Optional<?>) getTrinketComponentMethod.invoke(null, livingEntity);

            if (trinketComponentOpt.isPresent()) {
                Object trinketComponent = trinketComponentOpt.get();
                List<?> equippedList = (List<?>) getEquippedMethod.invoke(trinketComponent, ELYTRA_CHECK);

                if (!equippedList.isEmpty()) {
                    for (Object item : equippedList) {
                        Pair<Object, ItemStack> pair = (Pair<Object, ItemStack>) item;
                        ItemStack item_stack = pair.getRight();
                        if (item_stack.isOf(Items.ELYTRA)) return item_stack;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ItemStack.EMPTY;
    }
}
