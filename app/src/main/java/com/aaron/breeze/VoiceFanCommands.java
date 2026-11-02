package com.aaron.breeze;

import java.util.function.Consumer;

/** Immediate voice actions share Home's validated controls; rejected commands are never retained. */
final class VoiceFanCommands {
    static final long REPEAT_GUARD_MS=2500;
    interface Target {
        boolean eligible();
        boolean ready();
        boolean logicReady();
        boolean logicEnabled();
        boolean remote(int slot,Consumer<String> result);
        boolean logic(boolean enabled,Consumer<String> result);
        boolean synchronize(Consumer<String> result);
        String failure();
    }
    private long lastAcceptedAt=Long.MIN_VALUE;
    boolean dispatch(BedtimeVoiceCommand.Kind kind,long now,Target target,Consumer<String> result){
        if(kind!=BedtimeVoiceCommand.Kind.POWER&&kind!=BedtimeVoiceCommand.Kind.LOGIC
                &&kind!=BedtimeVoiceCommand.Kind.OSCILLATE&&kind!=BedtimeVoiceCommand.Kind.FIX_FAN)return false;
        if(!target.eligible()){result.accept("Not sent · listening is paused or disabled.");return false;}
        if(lastAcceptedAt!=Long.MIN_VALUE&&now-lastAcceptedAt<REPEAT_GUARD_MS){result.accept("Not sent · wait a moment before another fan command.");return false;}
        if(!target.ready()||(kind==BedtimeVoiceCommand.Kind.LOGIC&&!target.logicReady())){
            result.accept("Not sent · wait for a fresh, idle controller connection.");return false;
        }
        boolean accepted=switch(kind){
            case POWER -> target.remote(0,result);
            case OSCILLATE -> target.remote(4,result);
            case LOGIC -> target.logic(!target.logicEnabled(),result);
            case FIX_FAN -> target.synchronize(result);
            default -> false;
        };
        if(accepted)lastAcceptedAt=now;
        else result.accept(target.failure());
        return accepted;
    }
}
