package com.shopsign.scanner;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * 更多设置：物品图片开关、领地数据刷新速率、黑名单领地管理入口，
 * 以及实时的内存占用提示（图标缓存极小，但提醒玩家关注内存）。
 */
public class ExtraScreen extends Screen {
	private static final int[] REFRESH_MINUTES = {5, 15, 30, 60};

	private final ShopSignScannerClient client;

	protected ExtraScreen(ShopSignScannerClient client) {
		super(Component.literal("shopsignscanner · 更多设置"));
		this.client = client;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 125;
		int y = this.height / 2 - 70;
		int w = 250;
		int h = 20;
		int gap = 24;
		ModConfig config = client.getConfig();

		addRenderableWidget(Button.builder(
				Component.literal("领地数据刷新: " + config.landRefreshMinutes + " 分钟"),
				b -> {
					config.landRefreshMinutes = nextRefresh();
					config.save();
					rebuildWidgets();
				}).bounds(x, y, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("黑名单领地管理 >"),
				b -> this.minecraft.setScreen(new BlacklistScreen(client))).bounds(x, y + gap, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("完成").withStyle(ChatFormatting.GREEN),
				b -> onClose()).bounds(x, y + gap * 2, w, h).build());
	}

	private static Component onOff(boolean on) {
		return Component.literal(on ? "开" : "关")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private int nextRefresh() {
		int cur = client.getConfig().landRefreshMinutes;
		for (int i = 0; i < REFRESH_MINUTES.length; i++) {
			if (REFRESH_MINUTES[i] == cur) {
				return REFRESH_MINUTES[(i + 1) % REFRESH_MINUTES.length];
			}
		}
		return REFRESH_MINUTES[0];
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 88, 0xFFFFFFFF);

		// 实时内存提示（提醒玩家关注内存；超过 85% 变红）
		var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		long usedMb = heap.getUsed() / (1024 * 1024);
		long maxMb = heap.getMax() / (1024 * 1024);
		int pct = maxMb > 0 ? (int) (usedMb * 100 / maxMb) : 0;
		String mem = String.format(Locale.ROOT, "内存: %d MB / %d MB（%d%%）",
				usedMb, maxMb, pct);
		int memColor = pct >= 85 ? 0xFFFF5555 : pct >= 65 ? 0xFFFFFF55 : 0xFF55FF55;
		guiGraphics.drawCenteredString(this.font, mem, this.width / 2, this.height / 2 + 22, memColor);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(null);
	}
}
