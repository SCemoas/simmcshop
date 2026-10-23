package com.shopsign.scanner;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * shopsignscanner 设置界面：窗口开关、自动捕捉开关、捕捉频率、捕捉半径、
 * 捕捉按键（点击后按下新键即可，ESC 取消）、数据库连接状态、完成保存。
 */
public class SettingsScreen extends Screen {
	/** 抓取速率下限 10 秒（ModConfig.sanitize 同步钳制），不再提供更快的档位 */
	private static final int[] INTERVALS_SECONDS = {10, 30, 60};
	private static final int[] RADII = {16, 24, 32, 48, 64};
	private static final int[] WIDTHS = {200, 250, 300, 360, 420};

	private final ShopSignScannerClient client;
	/** 当前正在改绑的键位：null 表示不在改键状态 */
	private AssignTarget assigningKey;

	private enum AssignTarget {
		SCAN, BROWSE
	}

	protected SettingsScreen(ShopSignScannerClient client) {
		super(Component.literal("shopsignscanner · 设置"));
		this.client = client;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 125;
		int y = this.height / 2 - 130;
		int w = 250;
		int h = 20;
		int gap = 24;
		ModConfig config = client.getConfig();

		addRenderableWidget(Button.builder(
				Component.literal("左上角窗口: ").append(onOff(config.showWindow)),
				b -> {
					config.showWindow = !config.showWindow;
					config.save();
					rebuildWidgets();
				}).bounds(x, y, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("自动捕捉: ").append(onOff(config.autoScan)),
				b -> {
					config.autoScan = !config.autoScan;
					config.save();
					rebuildWidgets();
				}).bounds(x, y + gap, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("捕捉频率: " + (config.intervalTicks / 20) + " 秒"),
				b -> {
					config.intervalTicks = nextInterval();
					config.save();
					rebuildWidgets();
				}).bounds(x, y + gap * 2, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("捕捉半径: " + config.radius + " 格"),
				b -> {
					config.radius = nextRadius();
					config.save();
					rebuildWidgets();
				}).bounds(x, y + gap * 3, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("窗口宽度: " + config.panelWidth + " px"),
				b -> {
					config.panelWidth = nextWidth();
					config.save();
					rebuildWidgets();
				}).bounds(x, y + gap * 4, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("领地分表: ").append(onOff(config.landEnabled)),
				b -> {
					config.landEnabled = !config.landEnabled;
					config.save();
					rebuildWidgets();
				}).bounds(x, y + gap * 5, w, h).build());

		addRenderableWidget(Button.builder(
				assigningKey == AssignTarget.SCAN
						? Component.literal("> 请按新按键（ESC 取消）").withStyle(ChatFormatting.YELLOW)
						: Component.literal("捕捉按键: " + client.getScanKey().getTranslatedKeyMessage().getString()),
				b -> {
					assigningKey = AssignTarget.SCAN;
					rebuildWidgets();
				}).bounds(x, y + gap * 6, w, h).build());

		addRenderableWidget(Button.builder(
				assigningKey == AssignTarget.BROWSE
						? Component.literal("> 请按新按键（ESC 取消）").withStyle(ChatFormatting.YELLOW)
						: Component.literal("查询按键: " + client.getBrowseKey().getTranslatedKeyMessage().getString()),
				b -> {
					assigningKey = AssignTarget.BROWSE;
					rebuildWidgets();
				}).bounds(x, y + gap * 7, w, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("数据库: ").append(plainDbStatus()),
				b -> rebuildWidgets()).bounds(x, y + gap * 8, w, h).build());

		// 完成与更多设置并排
		int half = (w - 4) / 2;
		addRenderableWidget(Button.builder(
				Component.literal("完成").withStyle(ChatFormatting.GREEN),
				b -> onClose()).bounds(x, y + gap * 9, half, h).build());

		addRenderableWidget(Button.builder(
				Component.literal("更多设置 >"),
				b -> this.minecraft.setScreen(new ExtraScreen(client))).bounds(x + half + 4, y + gap * 9, half, h).build());

	}

	private int nextWidth() {
		int width = client.getConfig().panelWidth;
		for (int i = 0; i < WIDTHS.length; i++) {
			if (WIDTHS[i] == width) {
				return WIDTHS[(i + 1) % WIDTHS.length];
			}
		}
		return WIDTHS[0];
	}

	private static Component onOff(boolean on) {
		return Component.literal(on ? "开" : "关")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	/** statusText 含前缀 "数据库: "，去掉后只返回染色正文 */
	private Component plainDbStatus() {
		String s = client.getDbReporter().statusText();
		String body = s.startsWith("数据库: ") ? s.substring("数据库: ".length()) : s;
		ChatFormatting color;
		if (s.contains("已连接")) color = ChatFormatting.GREEN;
		else if (s.contains("待上传")) color = ChatFormatting.YELLOW;
		else if (s.contains("已禁用")) color = ChatFormatting.GRAY;
		else color = ChatFormatting.RED;
		return Component.literal(body).withStyle(color);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (assigningKey != null) {
			int code = event.key();
			if (code != GLFW.GLFW_KEY_ESCAPE) {
				var key = InputConstants.getKey(event);
				if (assigningKey == AssignTarget.SCAN) {
					client.getScanKey().setKey(key);
				} else {
					client.getBrowseKey().setKey(key);
				}
			}
			assigningKey = null;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	private int nextInterval() {
		int sec = client.getConfig().intervalTicks / 20;
		for (int i = 0; i < INTERVALS_SECONDS.length; i++) {
			if (INTERVALS_SECONDS[i] == sec) {
				return INTERVALS_SECONDS[(i + 1) % INTERVALS_SECONDS.length] * 20;
			}
		}
		return INTERVALS_SECONDS[0] * 20;
	}

	private int nextRadius() {
		int radius = client.getConfig().radius;
		for (int i = 0; i < RADII.length; i++) {
			if (RADII[i] == radius) {
				return RADII[(i + 1) % RADII.length];
			}
		}
		return RADII[0];
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		// ⚠️ drawString 颜色必须带 alpha 字节（1.21.11 缺 alpha = 全透明）
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 100, 0xFFFFFFFF);
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
