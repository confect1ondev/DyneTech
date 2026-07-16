package com.confect1on.dynetech.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import com.confect1on.dynetech.component.DTDataComponents;

/**
 * Detects item stacks that carry (directly or nested inside a container-like component) a
 * shrunken-structure or shrunken-entity reference. Used to refuse recursive nesting at capture
 * time — nesting would let a single shrink pull an unbounded blob dependency tree into storage,
 * plus allow duplication tricks on paste-back.
 */
public final class ShrunkenItemDetector {

    private ShrunkenItemDetector() {}

    public static boolean isShrunken(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.has(DTDataComponents.SHRUNKEN_STRUCTURE.get())) return true;
        if (stack.has(DTDataComponents.SHRUNKEN_ENTITY.get())) return true;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            for (ItemStack nested : container.nonEmptyItems()) {
                if (isShrunken(nested)) return true;
            }
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack nested : bundle.itemCopyStream().toList()) {
                if (isShrunken(nested)) return true;
            }
        }
        return false;
    }
}
