package com.aaron.breeze;

import java.util.Arrays;

/** Display-only smoothing of recorded per-sample body maxima; never changes its input arrays. */
public final class HighestBodySmoother {
    public static final long HALF_WINDOW_MS=60_000,MAX_SAMPLE_GAP_MS=35_000;
    private HighestBodySmoother(){}

    public static double highest(double first,double second,double third){
        double highest=Double.NEGATIVE_INFINITY;
        if(Double.isFinite(first))highest=Math.max(highest,first);
        if(Double.isFinite(second))highest=Math.max(highest,second);
        if(Double.isFinite(third))highest=Math.max(highest,third);
        return Double.isFinite(highest)?highest:Double.NaN;
    }

    public static final class Result {
        private final double[] values;
        private final int[] segments;
        private Result(double[] values,int[] segments){this.values=values;this.segments=segments;}
        public double valueAt(int index){return values[index];}
        public boolean sameSegment(int first,int second){return segments[first]>=0&&segments[first]==segments[second];}
        public int size(){return values.length;}
    }

    /** Mean of available samples within one minute either side, without crossing a missing reading or gap. */
    public static Result smooth(long[] times,double[] highest){
        return smooth(times,highest,null);
    }
    static Result smooth(long[] times,double[] highest,int[] groups){
        if(times==null||highest==null||times.length!=highest.length)throw new IllegalArgumentException("Smoothing needs matching timestamps and temperatures.");
        double[] values=new double[highest.length];Arrays.fill(values,Double.NaN);
        int[] segments=new int[highest.length];Arrays.fill(segments,-1);
        int start=0,segment=0;
        while(start<highest.length){
            if(!Double.isFinite(highest[start])){start++;continue;}
            int end=start+1;
            while(end<highest.length&&Double.isFinite(highest[end])&&continuous(times[end-1],times[end])&&(groups==null||groups[end-1]==groups[end]))end++;
            int left=start,right=start;double sum=0;
            for(int at=start;at<end;at++){
                long center=times[at];
                while(right<end&&(times[right]<=center||times[right]-center<=HALF_WINDOW_MS)){sum+=highest[right];right++;}
                while(left<right&&times[left]<center&&center-times[left]>HALF_WINDOW_MS){sum-=highest[left];left++;}
                values[at]=sum/(right-left);segments[at]=segment;
            }
            segment++;start=end;
        }
        return new Result(values,segments);
    }
    private static boolean continuous(long earlier,long later){long gap=later-earlier;return later>=earlier&&gap>=0&&gap<=MAX_SAMPLE_GAP_MS;}
}
