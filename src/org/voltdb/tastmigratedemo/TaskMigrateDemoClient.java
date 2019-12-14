package org.voltdb.tastmigratedemo;

import java.text.SimpleDateFormat;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2019 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

import java.util.Arrays;
import java.util.Date;
import java.util.Random;

import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.voltutil.schemabuilder.VoltDBSchemaBuilder;

public class TaskMigrateDemoClient {

    // @formatter:off 
    
    /**
     * DDL statements for Task Migrate Demo. Note that you could just
     * run this using SQLCMD, but the make this implementation easier to
     * re-create we do it Programmatically.
     */
    final String[] ddlStatements = {       
            
     // Create tables we need - note everything is partitioned on drone_id
     
      
     // This table has a list of locations drones aren't supposed to be 
     // near.
     "create table important_locations "
     + "(location_name varchar(20) not null primary key "
     + ",location_latlong GEOGRAPHY_POINT not null "
     + ",location_exclusion_zone_radius_m integer not null);",
                                 
     "CREATE TABLE drones (drone_id bigint not null primary key, declare_missing_date timestamp);",
            
     "PARTITION TABLE drones ON COLUMN drone_id;",
            
     "CREATE INDEX drone_idx1 ON drones (declare_missing_date);",
            
     "CREATE TABLE drone_locations " 
     + "MIGRATE TO TARGET old_drone_locations_tgt "
     + "(drone_id bigint not null " 
     + ",event_timestamp timestamp not null "
     + ",drone_location GEOGRAPHY_POINT not null " 
     + ",drone_speed_mps integer not null "
     + ",location_is_stale integer "
     + ",primary key (drone_id, event_timestamp));",
                    
     "PARTITION TABLE drone_locations ON COLUMN drone_id;",
     
     "CREATE INDEX drone_location_ts_idx ON drone_locations (drone_id, event_timestamp);",
     
     // Create export streams - they look like tables but are in fact 
     // 'at least once' queues to kafa, csv files, kinesis, etc
     
     // Missing drones in inserted into whenever a drone stops sending
     // us locations
     "CREATE STREAM missing_drones " 
     + "PARTITION ON COLUMN drone_id EXPORT TO TARGET tgt_missing_drones"
     + "(drone_id bigint not null ,event_timestamp timestamp not null "
     + ",drone_location GEOGRAPHY_POINT not null " 
     + ",drone_speed_mps integer not null "
     + ",location_is_stale integer);",
                           
     // location incursions is inserted into whenever a drone's
     // location is too close to an important location
     "CREATE STREAM location_incursions PARTITION ON COLUMN drone_id "
     + "EXPORT TO TARGET location_incursions_tgt  (drone_id bigint not null "
     + ",event_timestamp timestamp not null ,drone_location GEOGRAPHY_POINT not null "
     + ",drone_speed_mps integer not null ,location_name varchar(20) not null "
     + ",distance_from_metres bigint not null );",
            
     // This view summarises how many drones have been active per minute...
     "CREATE VIEW drone_activity AS "
     + "SELECT TRUNCATE(MINUTE, event_timestamp) activity_minute, COUNT(*) HOW_MANY "
     + "FROM drone_locations GROUP BY TRUNCATE(MINUTE, event_timestamp);",
                    
     // This view shows how many drones are marked as missing, or will be soon.
     "CREATE VIEW missing_drone_stats AS "
     + "SELECT TRUNCATE(MINUTE, declare_missing_date) declare_missing_date, COUNT(*) HOW_MANY "
     + "FROM drones GROUP BY TRUNCATE(MINUTE, declare_missing_date);",
     
     // This view shows whether any drones are missing
     "CREATE VIEW missing_drone_maxdate AS "
     + "SELECT MAX(declare_missing_date) declare_missing_date "
     + "FROM drones;",
     
     // Views allow us to access aggregates very efficently. In this case we want to
     // see the last activity date for each drone.
     "CREATE VIEW latest_drone_activity AS SELECT drone_id, max(event_timestamp) event_timestamp "
     + "FROM   drone_locations GROUP BY drone_id;",
     
     // In VoltDB views are like tables in that they have indexes...
     "CREATE INDEX lda_index1 ON latest_drone_activity(event_timestamp, drone_id); ",
            
    };

    
    /**
     * Procedure statements for Task Migrate Demo. Note that you
     * just run this using SQLCMD, but the make this implementation easier to
     * re-create we do it Programmatically.
     */
    final String[] procStatements = {
        
    // Creates a procedure from the java class taskmigratedemo.ReportLocation
    "CREATE PROCEDURE PARTITION ON TABLE drone_locations COLUMN drone_id FROM CLASS taskmigratedemo.ReportLocation;",

    // Creates a procedure from the java class taskmigratedemo.FindStaleDroneReports.
    "CREATE PROCEDURE DIRECTED FROM CLASS taskmigratedemo.FindStaleDroneReports;",

    // Creates a procedure from a SQL statement
    "CREATE PROCEDURE GetDrone PARTITION ON TABLE drone_locations COLUMN drone_id AS "
    + "SELECT * FROM drone_locations  WHERE drone_id = ? ORDER BY event_timestamp DESC;",
            
    // Creates a procedure from 2 SQL statements.
    "CREATE PROCEDURE GetStatus AS "
    + "BEGIN "
    + "SELECT Activity_minute last_active, how_many FROM drone_activity ORDER BY activity_minute; "
    + "SELECT HOW_MANY FROM missing_drone_stats WHERE DECLARE_MISSING_DATE IS NULL ORDER BY declare_missing_date; "
    + "END;",
                    
    // Schedules FindStaleDroneReports to run as a task.
    "CREATE TASK findStaleDronesTask ON SCHEDULE DELAY 250 MILLISECONDS PROCEDURE FindStaleDroneReports ON ERROR LOG RUN ON PARTITIONS;"
    
    };

    // @formatter:on

    // We only create the DDL and procedures if a call to testProcName with
    // testParams fails....
    final String testProcName = "GetDrone";
    final Object[] testParams = { new Long(1) };

    /**
     * VoltDB client object
     */
    Client client = null;

    /**
     * Pseudo random number generator
     */
    Random r = null;

    /**
     * Duration of test run, in seconds
     */
    int runSeconds = 0;

    /**
     * Target number of transactions per second, e.g. 70000.
     */
    long tps = 0;

    /**
     * How many drones to track
     */
    int size = 0;

    /**
     * Class to create a run a demo involving VoltDB Tasks and the MIGRATE command.
     * 
     * @param hostnames comma delimited list of hosts
     * @param randomSeed - used to make random behavior reproducible
     * @param runSeconds - how long to run, in seconds
     * @param tps - target Transactions Per Second, e.g. 50000
     * @param size - how many drones, e.g. 10000000
     */
    public TaskMigrateDemoClient(String hostnames, long randomSeed, int runSeconds, long tps, int size) {
        super();
        this.r = new Random(randomSeed);
        this.runSeconds = runSeconds;
        this.tps = tps;
        this.size = size;

        try {
            client = connectVoltDB(hostnames);
        } catch (Exception e) {
            error(e.getMessage());
        }

    }

    /**
     * Create and run the Task and Migrate demo.
     * Parameters: hostnames, tps, size, seconds
     * e.g.: localhost 30000 10000000 120
     */
    public static void main(String[] args) {

        msg("Parameters:" + Arrays.toString(args));

        final String hostnames = args[0];
        long startTps = Integer.parseInt(args[1]);
        final int size = Integer.parseInt(args[2]);
        final int seconds = Integer.parseInt(args[3]);

        TaskMigrateDemoClient ccMakeData = new TaskMigrateDemoClient(hostnames, 42, seconds, startTps, size);
        try {
            ccMakeData.createSchemaIfNeeded();
        } catch (Exception e) {
            error(e.toString());
            System.exit(1);

        }
        ccMakeData.loadData(size);

        ccMakeData.disconnect();
        ccMakeData = null;

        msg("Finished");

    }

    /**
     * Run the demo with 'size' drones.
     * @param size
     */
    private void loadData(int size) {

        final double baseLatitude = 51.4997138d;
        final double baseLongitude = -0.1436013d;

        double[] latitudes = new double[size];
        double[] longitudes = new double[size];

        for (int i = 0; i < latitudes.length; i++) {
            latitudes[i] = baseLatitude;
            longitudes[i] = baseLongitude + (i / 10000f) % 180;
        }

        int counter = 0;

        ComplainOnErrorCallback coec = new ComplainOnErrorCallback();

        final long endMs = System.currentTimeMillis() + (runSeconds * 1000);
        long currentMs = System.currentTimeMillis();
        int tpThisMs = 0;
        
        
        msg("Starting test run at " + tps + " transactions per second for " + runSeconds + " seconds");
        
        try {
            while (System.currentTimeMillis() < endMs) {

                if (tpThisMs++ > (tps / 1000)) {

                    // but sleep if we're moving too fast...
                    while (currentMs == System.currentTimeMillis()) {
                        try {
                            Thread.sleep(0, 50000);
                        } catch (InterruptedException e) {
                            error(e.getMessage());
                        }
                    }

                    currentMs = System.currentTimeMillis();
                    tpThisMs = 0;
                }

                int droneId = counter++ % size;
                int speedMps = r.nextInt(10);
                latitudes[droneId] = latitudes[droneId] + 0.01;

                client.callProcedure(coec, "ReportLocation", droneId, latitudes[droneId], longitudes[droneId],
                        speedMps);
                
                if (counter % 100000 == 0) {
                    ClientResponse status = client.callProcedure("GetStatus");
                    
                    msg ("Transaction #" + counter);
                    msg("Drone Activity By Minute:" );
                    msg(System. lineSeparator() + status.getResults()[0].toFormattedString());
                    msg("Missing Drones By Minute:"); 
                    msg(System. lineSeparator() + status.getResults()[1].toFormattedString());
                    
                }

            }
        } catch (Exception e) {
            error(e.getMessage());
        }

    }

    /**
     * Create schema and metadata used by demo if needed.
     * 
     * @throws Exception
     */
    private void createSchemaIfNeeded() throws Exception {

        VoltDBSchemaBuilder b = new VoltDBSchemaBuilder(ddlStatements, procStatements, null, "taskMigrateProcs.jar",
                client, "taskmigratedemo", testProcName, testParams, null);

        if (b.loadClassesAndDDLIfNeeded()) {

            client.callProcedure("important_locations.UPSERT", "Buckingham Palace", "POINT( -0.1436013 51.5013606)",
                    1000);
            client.callProcedure("important_locations.UPSERT", "10 Downing St", "POINT( -0.1298188 51.5033668)", 200);
            client.callProcedure("important_locations.UPSERT", "Parliament", "POINT(-0.1276976 51.4997138)", 500);

        }

    }

    private static Client connectVoltDB(String hostnames) throws Exception {
        Client client = null;
        ClientConfig config = null;

        try {
            msg("Logging into VoltDB");

            config = new ClientConfig(); 
            config.setMaxOutstandingTxns(20000);
            config.setMaxTransactionsPerSecond(500000);
            config.setTopologyChangeAware(true);
            config.setReconnectOnConnectionLoss(true);
            config.setHeavyweight(true);

            client = ClientFactory.createClient(config);
            String[] hostnameArray = hostnames.split(",");

            for (int i = 0; i < hostnameArray.length; i++) {
                msg("Connect to " + hostnameArray[i] + "...");
                try {
                    client.createConnection(hostnameArray[i]);
                } catch (Exception e) {
                    error(e.getMessage());
                }
            }

        } catch (Exception e) {
            error(e.getMessage());
            throw new Exception("VoltDB connection failed.." + e.getMessage(), e);
        }

        return client;

    }

    private void disconnect() {
        try {

            client.drain();
            client.close();

        } catch (Exception e) {
            error(e.getMessage());
        }
        client = null;

    }

  
    public static void error(String message) {
        msg("Error: " + message);

    }

    public static void msg(String message) {

        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date now = new Date();
        String strDate = sdfDate.format(now);
        System.out.println(strDate + ":" + message);
    }

}
