package com.aaron.breeze;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Display-only union of the returned sleep intervals. Never changes recorded samples. */
final class SleepWindow {
    final List<long[]> intervals=new ArrayList<>();
    SleepWindow(List<OuraData.Period> periods){
        List<long[]> sorted=new ArrayList<>();
        for(OuraData.Period period:periods)if(period.end>period.start)sorted.add(new long[]{period.start,period.end});
        sorted.sort(Comparator.comparingLong(interval->interval[0]));
        for(long[] interval:sorted){
            if(intervals.isEmpty()||interval[0]>intervals.get(intervals.size()-1)[1])intervals.add(interval);
            else intervals.get(intervals.size()-1)[1]=Math.max(intervals.get(intervals.size()-1)[1],interval[1]);
        }
    }
    boolean isEmpty(){return intervals.isEmpty();}
    long start(){return intervals.get(0)[0];}
    long end(){return intervals.get(intervals.size()-1)[1];}
    int intervalAt(long time){for(int i=0;i<intervals.size();i++){long[] interval=intervals.get(i);if(time>=interval[0]&&time<=interval[1])return i;}return -1;}
    boolean contains(long time){return intervalAt(time)>=0;}
    boolean connected(long first,long second){int interval=intervalAt(first);return interval>=0&&interval==intervalAt(second);}
}
