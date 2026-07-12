package com.confect1on.dynetech.client;

/** Simple client-side view flags. Not synced to server. */
public final class ClientSettings {

    private static boolean showSelectionBox = false;

    private ClientSettings() {}

    public static boolean showSelectionBox() {
        return showSelectionBox;
    }

    public static void toggleShowSelectionBox() {
        showSelectionBox = !showSelectionBox;
    }
}
