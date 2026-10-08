package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class WindowPolicyTest {
    private val screen=WindowBox(0,0,1080,2400)
    private fun system(title:String,b:WindowBox,pkg:String?="com.android.systemui",active:Boolean=false,focused:Boolean=false)=WindowMeta(3,title,pkg,active,focused,b)
    private val status=system("StatusBar",WindowBox(0,0,1080,90))
    private val navigation=system("NavigationBar0",WindowBox(0,2280,1080,2400))
    @Test fun passiveBarsDoNotBlockFullscreenLauncher() {
        assertFalse(WindowPolicy.blocks(status,screen,screen))
        assertFalse(WindowPolicy.blocks(navigation,screen,screen))
        assertEquals(WindowBox(0,90,1080,2280),WindowPolicy.contentArea(screen,listOf(status,navigation),screen))
    }
    @Test fun nullRootStillRequiresExactTitleAndGeometry() {
        assertTrue(WindowPolicy.passiveBar(status.copy(pkg=null),screen))
        assertFalse(WindowPolicy.passiveBar(status.copy(title="Unknown"),screen))
        assertFalse(WindowPolicy.passiveBar(status.copy(pkg="untrusted.app"),screen))
    }
    @Test fun expandedNotificationShadeRemainsBlocked() {
        assertTrue(WindowPolicy.blocks(status.copy(bounds=screen),screen,screen))
        assertTrue(WindowPolicy.blocks(status.copy(active=true),screen,screen))
        assertTrue(WindowPolicy.blocks(status.copy(focused=true),screen,screen))
    }
    @Test fun unknownThinAndCentralSystemWindowsRemainBlocked() {
        assertTrue(WindowPolicy.blocks(status.copy(title="Floating ball"),screen,screen))
        assertTrue(WindowPolicy.blocks(system("NavigationBar",WindowBox(0,500,1080,600)),screen,screen))
        assertTrue(WindowPolicy.blocks(system("Permission dialog",WindowBox(100,600,980,1800)),screen,screen))
    }
    @Test fun keyboardAndAccessibilityOverlaysAlwaysBlock() {
        assertTrue(WindowPolicy.blocks(status.copy(type=2),screen,screen))
        assertTrue(WindowPolicy.blocks(status.copy(type=4),screen,screen))
    }
    @Test fun landscapeSideNavigationIsCropped() {
        val landscape=WindowBox(0,0,2400,1080)
        val bar=system("NavigationBar",WindowBox(2280,0,2400,1080))
        assertTrue(WindowPolicy.passiveBar(bar,landscape))
        assertEquals(WindowBox(0,0,2280,1080),WindowPolicy.contentArea(landscape,listOf(bar),landscape))
    }
    @Test fun alreadyInsetAppAreaIsNotExpanded() {
        val inset=WindowBox(0,90,1080,2280)
        assertEquals(inset,WindowPolicy.contentArea(inset,listOf(status,navigation),screen))
    }
    @Test fun invalidOrOversizeBarsAreNotTrusted() {
        listOf(WindowBox(0,0,1080,300),WindowBox(-1,0,1080,90),WindowBox(0,0,1080,0)).forEach {
            assertFalse(WindowPolicy.passiveBar(status.copy(bounds=it),screen))
        }
    }
    private val miui=system("",WindowBox(0,2332,1080,2400),"com.miui.home")
    @Test fun observedMiuiBottomStripIsExcludedFromContent() {
        assertFalse(WindowPolicy.blocks(miui,screen,screen))
        assertEquals(WindowBox(0,0,1080,2332),WindowPolicy.contentArea(screen,listOf(miui),screen))
    }
    @Test fun miuiIdentityMustMatchExactly() {
        listOf(miui.copy(pkg=null),miui.copy(pkg="another.launcher"),miui.copy(title="Popup"),miui.copy(pkg="com.huawei.health")).forEach {
            assertTrue(WindowPolicy.blocks(it,screen,screen))
        }
    }
    @Test fun miuiFocusedOrInteractiveWindowsStillBlock() {
        listOf(miui.copy(active=true),miui.copy(focused=true),miui.copy(type=2),miui.copy(type=4)).forEach {
            assertTrue(WindowPolicy.blocks(it,screen,screen))
        }
    }
    @Test fun miuiBottomStripCannotMatchExpandedOrFloatingWindows() {
        listOf(screen,WindowBox(0,2200,1080,2400),WindowBox(0,0,1080,68),WindowBox(200,2332,1080,2400),WindowBox(0,2300,1080,2368)).forEach {
            assertTrue(WindowPolicy.blocks(miui.copy(bounds=it),screen,screen))
        }
    }
    @Test fun miuiLandscapeUntitledStripRemainsUnadapted() {
        val landscape=WindowBox(0,0,2400,1080)
        assertTrue(WindowPolicy.blocks(miui.copy(bounds=WindowBox(0,1060,2400,1080)),landscape,landscape))
    }
    private val untitledStatus=system("",WindowBox(0,0,1080,96))
    @Test fun observedUntitledSystemUiStatusIsCroppedWithMiuiNavigation() {
        assertFalse(WindowPolicy.blocks(untitledStatus,screen,screen))
        assertEquals(WindowBox(0,96,1080,2332),WindowPolicy.contentArea(screen,listOf(untitledStatus,miui),screen))
    }
    @Test fun untitledSystemUiBottomBarIsCropped() {
        val bottom=system("",WindowBox(0,2304,1080,2400))
        assertFalse(WindowPolicy.blocks(bottom,screen,screen))
        assertEquals(WindowBox(0,96,1080,2304),WindowPolicy.contentArea(screen,listOf(untitledStatus,bottom),screen))
    }
    @Test fun untitledUnknownOwnershipStillBlocks() {
        listOf(null,"unknown.package","com.miui.home").forEach {pkg ->
            assertTrue(WindowPolicy.blocks(untitledStatus.copy(pkg=pkg),screen,screen))
        }
    }
    @Test fun untitledSystemUiShadeAndOversizedWindowsBlock() {
        listOf(screen,WindowBox(0,0,1080,121),WindowBox(0,400,1080,496),WindowBox(50,0,1080,96)).forEach {
            assertTrue(WindowPolicy.blocks(untitledStatus.copy(bounds=it),screen,screen))
        }
    }
    @Test fun untitledSystemUiActiveFocusedKeyboardAndOverlayBlock() {
        listOf(untitledStatus.copy(active=true),untitledStatus.copy(focused=true),untitledStatus.copy(type=2),untitledStatus.copy(type=4)).forEach {
            assertTrue(WindowPolicy.blocks(it,screen,screen))
        }
    }
    @Test fun untitledSystemUiLandscapeSideBarIsCropped() {
        val landscape=WindowBox(0,0,2400,1080)
        val side=system("",WindowBox(2304,0,2400,1080))
        assertFalse(WindowPolicy.blocks(side,landscape,landscape))
        assertEquals(WindowBox(0,0,2304,1080),WindowPolicy.contentArea(landscape,listOf(side),landscape))
    }
    @Test fun untitledSystemUiDoesNotExpandInsetArea() {
        val inset=WindowBox(0,96,1080,2332)
        assertEquals(inset,WindowPolicy.contentArea(inset,listOf(untitledStatus,miui),screen))
    }
}
