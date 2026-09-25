package com.example.foodlog.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The small button that pulls one of every uneaten food out of an open container.
 *
 * <p>A plain click takes the items; holding the mouse down drags the button instead, and the spot
 * it was left at is remembered. {@link Button} fires its action on press, which would trigger the
 * take on the first pixel of a drag, so the two gestures are told apart here.</p>
 *
 * <p>Container screens consume mouse drags for their own slot logic and never pass them on to
 * their widgets, so the drag does not arrive through {@code mouseDragged}. {@link #followMouse} is
 * polled from the screen's render pass instead, and whether the button is still held is read
 * straight from GLFW - a drag that ends away from the button is never delivered as a release
 * either.</p>
 */
final class TakeOutButton extends Button {

    static final int WIDTH = 64;
    static final int HEIGHT = 16;

    /** How far the mouse may wander before the gesture counts as a drag rather than a click. */
    private static final int DRAG_THRESHOLD = 3;

    private final Runnable action;

    private boolean pressed;
    private boolean dragged;
    private int originX;
    private int originY;
    private double grabX;
    private double grabY;

    TakeOutButton(int x, int y, Runnable action) {
        super(x, y, WIDTH, HEIGHT, Component.translatable("foodlog.button.take"), button -> {
        }, Button.DEFAULT_NARRATION);
        this.action = action;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.active || !this.visible || !this.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        this.pressed = true;
        this.dragged = false;
        this.originX = this.getX();
        this.originY = this.getY();
        this.grabX = mouseX - this.getX();
        this.grabY = mouseY - this.getY();
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.pressed) {
            release();
        }
        return true;
    }

    /**
     * Follows the cursor while the button is held. Called once per frame from the owning screen,
     * in the same coordinate space the button is positioned in.
     */
    void followMouse(double mouseX, double mouseY) {
        if (!this.pressed) {
            return;
        }
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            release();
            return;
        }
        this.setX((int) Math.round(mouseX - this.grabX));
        this.setY((int) Math.round(mouseY - this.grabY));
        if (Math.abs(this.getX() - this.originX) > DRAG_THRESHOLD
                || Math.abs(this.getY() - this.originY) > DRAG_THRESHOLD) {
            this.dragged = true;
        }
    }

    private void release() {
        this.pressed = false;
        if (this.dragged) {
            ClientSettings.setTakeButtonPosition(this.getX(), this.getY());
        } else {
            this.action.run();
        }
    }
}
