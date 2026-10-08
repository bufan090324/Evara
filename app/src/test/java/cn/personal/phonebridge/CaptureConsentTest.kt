package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class CaptureConsentTest {
    private class Store(var value:Boolean=false) {fun policy()=CaptureConsent(read={value},write={value=it})}
    @Test fun defaultDoesNotGrantCapture() {val p=Store().policy();p.begin();assertFalse(p.remembered);assertFalse(p.allowed)}
    @Test fun rememberAloneDoesNotOpenSession() {val p=Store().policy();p.remember(true);assertTrue(p.remembered);assertFalse(p.allowed)}
    @Test fun rememberedChoiceAppliesToNewExplicitSession() {val p=Store().policy();p.remember(true);p.begin();assertTrue(p.allowed)}
    @Test fun stoppedSessionKeepsChoiceButRevokesCapture() {val p=Store().policy();p.remember(true);p.begin();p.end();assertTrue(p.remembered);assertFalse(p.allowed)}
    @Test fun restartRemembersChoiceWithoutRestoringSession() {val s=Store();val p=s.policy();p.remember(true);p.begin();val restarted=s.policy();assertTrue(restarted.remembered);assertFalse(restarted.allowed);restarted.begin();assertTrue(restarted.allowed)}
    @Test fun disablingImmediatelyRevokesActiveSession() {val p=Store().policy();p.remember(true);p.begin();p.remember(false);assertFalse(p.allowed);p.end();p.begin();assertFalse(p.allowed)}
    @Test fun enablingDuringSessionTakesEffectWithoutReconnect() {val p=Store().policy();p.begin();assertFalse(p.allowed);p.remember(true);assertTrue(p.allowed)}
    @Test fun oldStoppedPolicyCannotRestoreAuthorizationByChangingPreference() {val s=Store(true);val old=s.policy();old.begin();old.end();val current=s.policy();current.begin();current.remember(false);current.remember(true);assertFalse(old.allowed);assertTrue(current.allowed)}
}
