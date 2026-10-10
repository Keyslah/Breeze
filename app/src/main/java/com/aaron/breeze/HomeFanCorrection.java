package com.aaron.breeze;

/** Only the state changes established by the controller's manual-command contract. */
public final class HomeFanCorrection {
    private HomeFanCorrection(){}
    public static Integer afterManual(int slot,int before,int retainedOn){
        if(!FanSetting.valid(before))return null;
        return switch(slot){
            case 0 -> before>0?Integer.valueOf(0):FanSetting.validOn(retainedOn)?Integer.valueOf(retainedOn):null;
            case 1 -> before>=1?Integer.valueOf(before==FanSetting.MAX?1:before+1):null;
            case 2 -> before>=1?Integer.valueOf(before==1?FanSetting.MAX:before-1):null;
            case 4,5 -> Integer.valueOf(before);
            default -> null;
        };
    }
}
