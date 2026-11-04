package com.aaron.breeze;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class GraphTimeAxis {
    private static final ZoneId CENTRAL=ZoneId.of("America/Chicago");
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.US).withZone(CENTRAL);
    static String label(long time){return CLOCK.format(Instant.ofEpochMilli(time));}
    /** Local even-hour marks; elapsed timestamps remain correct through DST changes. */
    static List<Long> ticks(long start,long end){
        List<Long> ticks=new ArrayList<>();
        ZonedDateTime hour=Instant.ofEpochMilli(start).atZone(CENTRAL).withMinute(0).withSecond(0).withNano(0).plusHours(1);
        while(hour.toInstant().toEpochMilli()<end){
            if(hour.getHour()%2==0)ticks.add(hour.toInstant().toEpochMilli());
            hour=hour.plusHours(1);
        }
        return ticks;
    }
}
