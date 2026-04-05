package com.skidreport.model;

/**
 * One near-miss event row stored in SQLite and written to Excel.
 * Represents one second where two aircraft were within 500 feet of each other.
 */
public class NearMissEvent {
    public String date;
    public String startTime;
    public String endTime;
    public String tail1;
    public String tail2;
    public double lat1, lon1, alt1, ias1;
    public double lat2, lon2, alt2, ias2;
    public double minDistanceFt;
    public String yearMonth;
}
