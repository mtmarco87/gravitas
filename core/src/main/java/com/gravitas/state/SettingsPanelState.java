package com.gravitas.state;

import java.util.EnumMap;

public final class SettingsPanelState {

    private final EnumMap<SettingsMenuPage, Integer> selections = new EnumMap<>(SettingsMenuPage.class);

    private boolean open;
    private SettingsMenuPage page = SettingsMenuPage.MAIN;
    private boolean editMode;
    private boolean dragging;
    private boolean positioned;
    private float x;
    private float y;
    private float width;
    private float height;
    private float dragOffsetX;
    private float dragOffsetY;
    private boolean pressedInside;
    private float pressX;
    private float pressY;
    private String statusText = "";
    private float statusTimer;
    private double editStartValue;
    private String manualInputBuffer = "";

    public SettingsPanelState() {
        for (SettingsMenuPage value : SettingsMenuPage.values()) {
            selections.put(value, 0);
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public SettingsMenuPage getPage() {
        return page;
    }

    public void setPage(SettingsMenuPage page) {
        this.page = page;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public int getSelection(SettingsMenuPage page) {
        return selections.getOrDefault(page, 0);
    }

    public int getCurrentSelection() {
        return getSelection(page);
    }

    public void setSelection(SettingsMenuPage page, int selection) {
        selections.put(page, selection);
    }

    public void setCurrentSelection(int selection) {
        setSelection(page, selection);
    }

    public void resetSelection(SettingsMenuPage page) {
        setSelection(page, 0);
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    public boolean isPositioned() {
        return positioned;
    }

    public void setPositioned(boolean positioned) {
        this.positioned = positioned;
    }

    public float getX() {
        return x;
    }

    public void setX(float x) {
        this.x = x;
    }

    public float getY() {
        return y;
    }

    public void setY(float y) {
        this.y = y;
    }

    public float getWidth() {
        return width;
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public float getHeight() {
        return height;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public float getDragOffsetX() {
        return dragOffsetX;
    }

    public void setDragOffsetX(float dragOffsetX) {
        this.dragOffsetX = dragOffsetX;
    }

    public float getDragOffsetY() {
        return dragOffsetY;
    }

    public void setDragOffsetY(float dragOffsetY) {
        this.dragOffsetY = dragOffsetY;
    }

    public boolean isPressedInside() {
        return pressedInside;
    }

    public void setPressedInside(boolean pressedInside) {
        this.pressedInside = pressedInside;
    }

    public float getPressX() {
        return pressX;
    }

    public void setPressX(float pressX) {
        this.pressX = pressX;
    }

    public float getPressY() {
        return pressY;
    }

    public void setPressY(float pressY) {
        this.pressY = pressY;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatus(String statusText, float statusTimer) {
        this.statusText = statusText;
        this.statusTimer = statusTimer;
    }

    public void clearStatus() {
        setStatus("", 0f);
    }

    public boolean isStatusVisible() {
        return statusTimer > 0f && !statusText.isEmpty();
    }

    public float getStatusTimer() {
        return statusTimer;
    }

    public void setStatusTimer(float statusTimer) {
        this.statusTimer = statusTimer;
    }

    public double getEditStartValue() {
        return editStartValue;
    }

    public void setEditStartValue(double editStartValue) {
        this.editStartValue = editStartValue;
    }

    public String getManualInputBuffer() {
        return manualInputBuffer;
    }

    public void setManualInputBuffer(String manualInputBuffer) {
        this.manualInputBuffer = manualInputBuffer;
    }

    public float resolvePanelX(int screenWidth, float panelWidth) {
        if (!positioned) {
            return screenWidth * 0.5f - panelWidth * 0.5f;
        }
        clampPosition(screenWidth, Integer.MAX_VALUE, panelWidth, height);
        return x;
    }

    public float resolvePanelY(int screenHeight, float panelHeight) {
        if (!positioned) {
            return screenHeight * 0.5f - panelHeight * 0.5f;
        }
        clampPosition(Integer.MAX_VALUE, screenHeight, width, panelHeight);
        return y;
    }

    public void setPanelBounds(float x, float y, float width, float height, int screenWidth, int screenHeight) {
        this.width = width;
        this.height = height;
        if (!positioned) {
            this.x = x;
            this.y = y;
        }
        clampPosition(screenWidth, screenHeight, width, height);
    }

    public boolean contains(float x, float y) {
        return x >= this.x && x <= this.x + width && y >= this.y && y <= this.y + height;
    }

    public void clampPosition(int screenWidth, int screenHeight, float panelWidth, float panelHeight) {
        float margin = 12f;
        if (screenWidth != Integer.MAX_VALUE) {
            float maxX = Math.max(margin, screenWidth - panelWidth - margin);
            x = Math.max(margin, Math.min(x, maxX));
        }
        if (screenHeight != Integer.MAX_VALUE) {
            float maxY = Math.max(margin, screenHeight - panelHeight - margin);
            y = Math.max(margin, Math.min(y, maxY));
        }
    }
}