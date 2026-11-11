package dev.optimus.client;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import dev.optimus.core.control.RemoteCommand;

/** Real GUI types, remote acknowledgement policy and reversible focus-loss settings. */
public final class RemoteControlSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        expect(RemoteControl.closeOnStart(new GuiChat()),"authenticated remote start can close bot chat/debug screen");
        expect(RemoteControl.closeOnStart(new GuiIngameMenu()),"authenticated remote start can close focus-loss pause menu");
        expect(!RemoteControl.closeOnStart(new GuiScreen() { }),"other GUI types remain blocked");
        expect(!RemoteControl.closeOnStart(null),"no GUI needs no dismissal");
        expect(RemoteControl.closeWhileRunning(true,new GuiIngameMenu()),"Escape menu is dismissed throughout an active run");
        expect(!RemoteControl.closeWhileRunning(false,new GuiIngameMenu()),"F9/stopped bot leaves Escape menu open");
        expect(!RemoteControl.closeWhileRunning(true,new GuiChat()),"chat editing is not closed by the Escape-menu policy");
        expect(!RemoteControl.closeWhileRunning(true,new GuiScreen() { }),"other interfaces remain paused");
        expect(!RemoteControl.closeWhileRunning(true,null),"running without menu needs no dismissal");
        expect(!RemoteControl.closeAfterRespawn(true,new GuiChat()),"respawn menu cleanup preserves chat");
        expect(!RemoteControl.closeAfterRespawn(true,new net.minecraft.client.gui.GuiGameOver()),"respawn cleanup never clicks or closes the death screen");
        expect(!RemoteControl.closeAfterRespawn(true,new GuiScreen() { }),"respawn cleanup leaves unrelated interfaces open");
        expect(!RemoteControl.closeAfterRespawn(true,null),"no respawn menu needs no dismissal");
        expect(!RemoteControl.replyTo("start"),"start has no public acknowledgement");
        expect(!RemoteControl.replyTo(RemoteCommand.parse("!o startZ","Bot_One")[0]),"start aliases also have no public acknowledgement");
        for(String action:new String[]{"say","stop","status","list","add","remove"})
            expect(RemoteControl.replyTo(action),"other explicit command replies remain available");
        RemoteControl control=new RemoteControl();
        expect(control.restorePause(true),"stopping a local session preserves focus-loss preference");
        expect(!control.keepActive(true),"remote run disables background pause in memory");
        expect(!control.keepActive(false),"repeated activation does not replace original preference");
        expect(control.restorePause(false),"remote stop restores original enabled preference");
        expect(!control.restorePause(false),"repeated stop does not restore stale state");
        expect(!control.keepActive(false) && !control.restorePause(false),"original disabled preference stays disabled after remote stop");
        expect(!control.keepActive(true) && control.restorePause(false),"next remote session independently restores preference");
        System.out.println("PASS: "+checks+" remote GUI, background activity and quiet-start checks");
    }
}