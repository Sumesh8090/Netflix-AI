package com.watchspaces.core;

public final class TimeFmt {
    private TimeFmt() { }

    /** 252 -> "00:04:12" */
    public static String hms(long seconds) {
        long s = Math.max(0, seconds);
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
