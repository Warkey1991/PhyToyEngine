package com.team.phytoy.camera

import org.junit.Assert.*
import org.junit.Test

class FullAccessControllerTest {
    @Test fun allStylesAreUsableImmediatelyAndAfterLifecycleChanges() {
        val controller = FullAccessController()
        fun assertAccess() {
            assertEquals(CameraStyle.entries.toSet(), controller.currentState().unlockedStyles)
            assertTrue(controller.currentState().entitlementsLoaded)
            assertTrue(CameraStyle.entries.all(controller::isUnlocked))
            assertTrue(controller.currentState().pendingProductIds.isEmpty())
        }
        assertAccess()
        controller.onPause()
        controller.onResume()
        controller.restorePurchases()
        assertAccess()
        assertTrue(FullAccessController().currentState().allCamerasUnlocked)
    }

    @Test fun noProductCanBePurchased() {
        val controller = FullAccessController()
        assertTrue(controller.currentState().products.isEmpty())
        assertFalse(controller.currentState().verificationConfigured)
        for (product in StyleProducts.allProductIds) {
            assertFalse(controller.canPurchase(product))
            assertFalse(controller.currentState().canPurchase(product))
        }
        assertTrue(CameraEntitlementPolicy.canUse("a_future_camera", false,
            controller.currentState().ownedProductIds))
    }
}
