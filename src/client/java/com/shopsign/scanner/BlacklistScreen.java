package com.shopsign.scanner;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑名单领地管理：输入领地名添加（与地图站/窗口显示的领地名一致即可，
 * 忽略大小写），列表可逐条删除。黑名单内的领地商店不采集、不入库。
 */
public class BlacklistScreen extends Screen {
	private static final int ROW_HEIGHT = 20;

	private final ShopSignScannerClient client;
	private EditBox inputBox;
	private final List<Rect> deleteButtons = new ArrayList<>();
	private double hoverX = -1;
	private double hoverY = -1;
	private int scrollOffset;
	private String feedback;
	private long feedbackUntil;

	private record Rect(int x, int y, int w, int h) {
		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}
	}

	public BlacklistScreen(ShopSignScannerClient client) {
		super(Component.literal("shopsignscanner · 黑名单领地"));
		this.client = client;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 125;
		int y = this.height / 2 - 90;
		int w = 250;

		inputBox = new EditBox(this.font, x + 1, y + 22, w - 56, 16, Component.literal("领地名"));
		inputBox.setMaxLength(64);
		inputBox.setBordered(false);
		inputBox.setTextColor(0xFF66D9FF);
		inputBox.setHint(Component.literal("输入领地名（如：青禾）…"));
		addWidget(inputBox);
		setInitialFocus(inputBox);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		hoverX = mouseX;
		hoverY = mouseY;

		int x = this.width / 2 - 125;
		int y = this.height / 2 - 90;
		int w = 250;
		List<String> blacklist = client.getConfig().landBlacklist;

		// 边框 + 背景
		g.fill(x + 1, y + 1, x + w - 1, y + 180, 0xE60D141B);
		g.fill(x, y, x + w, y + 1, 0xFF2E6E8E);
		g.fill(x, y + 180, x + w, y + 181, 0xFF2E6E8E);
		g.fill(x, y, x + 1, y + 181, 0xFF2E6E8E);
		g.fill(x + w - 1, y, x + w, y + 181, 0xFF2E6E8E);
		g.fill(x + 1, y + 1, x + w - 1, y + 15, 0xF010202C);
		g.drawString(this.font, "黑名单领地（这些领地的商店不会被采集）", x + 6, y + 4, 0xFF66D9FF, true);

		// 输入框底色 + 添加按钮
		g.fill(x + 1, y + 20, x + w - 49, y + 40, 0xFF0A121A);
		g.fill(x + 1, y + 39, x + w - 49, y + 40, 0xFF2E6E8E);
		inputBox.setX(x + 3);
		inputBox.setY(y + 24);
		inputBox.setWidth(w - 58);
		inputBox.render(g, mouseX, mouseY, partialTick);
		Rect addBtn = new Rect(x + w - 45, y + 21, 41, 18);
		boolean addHover = addBtn.contains(mouseX, mouseY);
		g.fill(addBtn.x, addBtn.y, addBtn.x + addBtn.w, addBtn.y + addBtn.h, addHover ? 0xFF2A4A5E : 0xFF16303E);
		g.fill(addBtn.x, addBtn.y + addBtn.h - 1, addBtn.x + addBtn.w, addBtn.y + addBtn.h, 0xFF2E6E8E);
		g.drawString(this.font, "添加", addBtn.x + 9, addBtn.y + 5, 0xFFFFFFFF, false);

		// 列表（滚动）
		int listTop = y + 46;
		int listBottom = y + 148;
		g.fill(x + 1, listTop, x + w - 1, listBottom, 0x660A121A);
		int visible = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		int maxOffset = Math.max(0, blacklist.size() - visible);
		if (scrollOffset > maxOffset) scrollOffset = maxOffset;
		if (scrollOffset < 0) scrollOffset = 0;

		deleteButtons.clear();
		if (blacklist.isEmpty()) {
			g.drawString(this.font, "黑名单为空。添加领地名后，这些领地的商店将不再被采集。", x + 6, listTop + 6, 0xFF93A4B3, true);
		} else {
			for (int i = 0; i < visible; i++) {
				int idx = scrollOffset + i;
				if (idx >= blacklist.size()) break;
				int ry = listTop + 3 + i * ROW_HEIGHT;
				String name = blacklist.get(idx);
				g.drawString(this.font, "· " + name, x + 8, ry + 3, 0xFFD8D8D8, true);
				Rect del = new Rect(x + w - 40, ry, 32, 15);
				deleteButtons.add(new Rect(del.x, del.y, del.w, del.h));
				boolean hover = del.contains(mouseX, mouseY);
				g.fill(del.x, del.y, del.x + del.w, del.y + del.h, hover ? 0xFF5A2A2A : 0xFF301A1A);
				g.fill(del.x, del.y + del.h - 1, del.x + del.w, del.y + del.h, 0xFF8E3A3A);
				g.drawString(this.font, "删除", del.x + 5, del.y + 4, 0xFFFFAAAA, false);
			}
			if (maxOffset > 0) {
				g.drawString(this.font, "（滚轮查看更多，共 " + blacklist.size() + " 条）", x + 6, listBottom - 11, 0xFF667788, false);
			}
		}

		// 反馈 / 提示
		String tip;
		if (feedback != null && System.currentTimeMillis() < feedbackUntil) {
			tip = feedback;
		} else {
			tip = "修改立即生效并保存；ESC 返回上一页。";
		}
		g.drawString(this.font, tip, x + 6, y + 155, 0xFF55FF55, true);
		g.drawString(this.font, "领地名与游戏内/地图站显示一致即可，忽略大小写。", x + 6, y + 168, 0xFF93A4B3, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double x = event.x();
		double y = event.y();
		int px = this.width / 2 - 125;
		int py = this.height / 2 - 90;
		List<String> blacklist = client.getConfig().landBlacklist;

		// 添加
		if (new Rect(px + 205, py + 21, 41, 18).contains(x, y)) {
			addCurrent();
			return true;
		}
		// 删除
		for (int i = 0; i < deleteButtons.size(); i++) {
			if (deleteButtons.get(i).contains(x, y)) {
				int idx = scrollOffset + i;
				if (idx >= 0 && idx < blacklist.size()) {
					String removed = blacklist.remove(idx);
					client.getConfig().save();
					scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, blacklist.size() - 1)));
					showFeedback("已移除: " + removed);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0) {
			scrollOffset -= (int) Math.signum(scrollY) * 2;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void addCurrent() {
		String name = inputBox.getValue().trim();
		if (name.isEmpty()) {
			showFeedback("请先输入领地名");
			return;
		}
		List<String> blacklist = client.getConfig().landBlacklist;
		boolean dup = false;
		for (String e : blacklist) {
			if (e.equalsIgnoreCase(name)) {
				dup = true;
				break;
			}
		}
		if (dup) {
			showFeedback("已在黑名单中: " + name);
			return;
		}
		blacklist.add(name);
		client.getConfig().save();
		inputBox.setValue("");
		scrollOffset = Math.max(0, blacklist.size() - 1);
		showFeedback("已添加: " + name);
	}

	private void showFeedback(String text) {
		feedback = text;
		feedbackUntil = System.currentTimeMillis() + 2500;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && inputBox != null && inputBox.isFocused()) {
			addCurrent();
			return true;
		}
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && inputBox != null && inputBox.isFocused()) {
			setFocused(null);
			return true;
		}
		return super.keyPressed(event);
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
