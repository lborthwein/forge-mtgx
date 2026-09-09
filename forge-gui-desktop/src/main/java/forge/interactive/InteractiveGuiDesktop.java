/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import forge.GuiDesktop;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.util.FSerializableFunction;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Catches the small number of engine paths that call the process-wide GUI chooser rather
 * than the active player's {@link IGuiGame}. In particular, HumanCostDecision has a
 * same-zone library-cost choice on this seam. Falling through to GuiDesktop here would
 * open an invisible Swing dialog in the server process.
 */
final class InteractiveGuiDesktop extends GuiDesktop {
    private volatile InteractiveGuiGame gameGui;

    void bind(final InteractiveGuiGame gui) {
        if (gameGui != null && gameGui != gui) {
            throw new IllegalStateException("interactive desktop may bind only one game GUI");
        }
        gameGui = Objects.requireNonNull(gui);
    }

    private InteractiveGuiGame gui() {
        final InteractiveGuiGame value = gameGui;
        if (value == null) {
            throw new IllegalStateException("global GUI choice requested before interactive game binding");
        }
        return value;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                final List<String> options, final int defaultOption) {
        return gui().showOptionDialog(message, title, icon, options, defaultOption);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                  final String initialInput, final List<String> inputOptions,
                                  final boolean isNumeric) {
        return gui().showInputDialog(message, title, icon, initialInput, inputOptions, isNumeric);
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max,
                                  final Collection<T> choices, final Collection<T> selected,
                                  final FSerializableFunction<T, String> display) {
        return gui().globalChoices(message, min, max, choices, selected, display);
    }

    @Override
    public <T> List<T> order(final String title, final String top,
                             final int remainingObjectsMin, final int remainingObjectsMax,
                             final List<T> sourceChoices, final List<T> destChoices) {
        return gui().globalOrder(title, top, remainingObjectsMin, remainingObjectsMax,
                sourceChoices, destChoices);
    }

    @Override
    public void showImageDialog(final ISkinImage image, final String message,
                                final String title) {
        gui().message(message, title);
    }

    @Override
    public void showCardList(final String title, final String message,
                             final List<PaperCard> list) {
        gui().getChoices(title + "\n" + message, -1, -1, list, null, PaperCard::getName);
    }

    @Override
    public PaperCard chooseCard(final String title, final String message,
                                final List<PaperCard> list) {
        final List<PaperCard> selected = gui().globalChoices(title + "\n" + message,
                1, 1, list, null, PaperCard::getName);
        return selected.isEmpty() ? null : selected.get(0);
    }

    @Override
    public IGuiGame getNewGuiGame() {
        return gui();
    }
}
