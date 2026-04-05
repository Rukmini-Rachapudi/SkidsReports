package com.skidreport.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DatabaseManager
 *
 * Responsible for creating the SQLite schema.
 * Called once at the start of NearMissReportGenerator.
 *
 * Tables created:
 *   flight_records   -- one qualifying row per second per aircraft
 *   near_miss_events -- detected near-miss pairs
 */
public class DatabaseManager {

    /**
     * Creates tables and indexes from scratch (drops existing first).
     * Use when starting a completely fresh run.
     */
    public static void createTablesAndIndexes(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS near_miss_events");
            st.execute("DROP TABLE IF EXISTS flight_records");
        }
        createSchema(conn);
    }

    /**
     * Creates tables and indexes only if they don't already exist.
     * Safe to call when DB may already have data (e.g. from SkidReportGenerator).
     */
    public static void createTablesIfAbsent(Connection conn) throws SQLException {
        createSchema(conn);
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {

            // ----------------------------------------------------------------
            // TABLE: flight_records
            // One row per second per aircraft (after trigger filters applied)
            // ----------------------------------------------------------------
            st.execute(
                "CREATE TABLE IF NOT EXISTS flight_records ("               +
                "  id         INTEGER PRIMARY KEY AUTOINCREMENT,"            +
                "  tail       TEXT    NOT NULL,"                             +
                "  local_date TEXT    NOT NULL,"                             +
                "  local_time TEXT    NOT NULL,"                             +
                "  latitude   REAL    NOT NULL,"                             +
                "  longitude  REAL    NOT NULL,"                             +
                "  alt_msl    REAL    NOT NULL,"                             +
                "  ias        REAL    NOT NULL,"                             +
                "  e1_rpm     REAL    NOT NULL"                              +
                ")"
            );

            // Indexes for fast pairwise lookups by date and time
            st.execute("CREATE INDEX IF NOT EXISTS idx_fr_datetime ON flight_records(local_date, local_time)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_fr_tail     ON flight_records(tail)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_fr_tail_dt  ON flight_records(tail, local_date, local_time)");

            // ----------------------------------------------------------------
            // TABLE: near_miss_events
            // One row per detected near-miss event (grouped points within 1 min)
            // ----------------------------------------------------------------
            st.execute(
                "CREATE TABLE IF NOT EXISTS near_miss_events ("           +
                "  id          INTEGER PRIMARY KEY AUTOINCREMENT,"        +
                "  local_date  TEXT    NOT NULL,"                         +
                "  start_time  TEXT    NOT NULL,"                         +
                "  end_time    TEXT    NOT NULL,"                         +
                "  tail1       TEXT    NOT NULL,"                         +
                "  tail2       TEXT    NOT NULL,"                         +
                "  latitude1   REAL,"                                     +
                "  longitude1  REAL,"                                     +
                "  alt1        REAL,"                                     +
                "  ias1        REAL,"                                     +
                "  latitude2   REAL,"                                     +
                "  longitude2  REAL,"                                     +
                "  alt2        REAL,"                                     +
                "  ias2        REAL,"                                     +
                "  min_dist_ft REAL,"                                     +
                "  year_month  TEXT"                                      +
                ")"
            );

            st.execute("CREATE INDEX IF NOT EXISTS idx_nm_pair    ON near_miss_events(tail1, tail2)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_nm_date    ON near_miss_events(local_date)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_nm_ym      ON near_miss_events(year_month)");

            System.out.println("  Tables created  : flight_records, near_miss_events");
            System.out.println("  Indexes created : date/time/tail columns");
        }
    }

    public static void wipeDatabase(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS near_miss_events");
            st.execute("DROP TABLE IF EXISTS flight_records");
            System.out.println("  Database wiped  : flight_records, near_miss_events");
        } catch (SQLException e) {
            System.err.println("  [WARN] Failed to wipe database tables: " + e.getMessage());
        }
        // VACUUM reclaims disk space — must run outside a transaction
        try {
            conn.commit();
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                System.out.println("  Running VACUUM to reclaim disk space...");
                st.execute("VACUUM");
                System.out.println("  VACUUM complete.");
            }
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            System.err.println("  [WARN] VACUUM failed: " + e.getMessage());
        }
    }
}
