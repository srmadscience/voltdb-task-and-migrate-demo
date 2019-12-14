package taskmigratedemo;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
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

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.types.GeographyPointValue;

/**
 * Volt Procedure to track latest position of a drone and inform third parties
 * if it is too close to a place of interest
 * 
 * @author drolfe
 *
 */
public class ReportLocation extends VoltProcedure {

    // @formatter:off 
    
    // Create or update a record in the DRONES table; set a date 2 minutes in the future for
    // when we will report it missing. 
    public static final SQLStmt upsertDrone = new SQLStmt(
            "upsert into drones (drone_id,declare_missing_date ) values (?, DATEADD(MINUTE,2,NOW));");
        
    // Report the location of the drone
    public static final SQLStmt addLocation = new SQLStmt(
            "insert into drone_locations (drone_id,event_timestamp, drone_location,drone_speed_mps ) values (?,?,?,?)");
        
    // Find drones whose latest location is too cloxe to an important location
    public static final SQLStmt findIncursions = new SQLStmt(
          "insert into location_incursions" + 
          "(drone_id, event_timestamp, drone_location, drone_speed_mps, location_name, distance_from_metres )" + 
          "Select dl.drone_id, NOW, il.location_latlong, dl.drone_speed_mps,  il.location_name," + 
          "distance(il.location_latlong,dl.drone_location)" + 
          "          from important_locations il" + 
          "             , drone_locations dl" + 
          "          where dl.drone_id = ? " +
          "          and   dl.event_timestamp = ? " +
          "          and distance(il.location_latlong,dl.drone_location) < il.location_exclusion_zone_radius_m ;");

    // Find the 'nth' oldest record for a give drone - we migrate this record and any older ones.
    public static final SQLStmt findDeletePoint = new SQLStmt(
          "select event_timestamp from drone_locations where drone_id = ? order by drone_id, event_timestamp desc limit 1 offset ?;");

    // move records from drone_locations to drone_location's export target
    public static final SQLStmt migrateOldRecords = new SQLStmt(
          "migrate from drone_locations where drone_id = ? and event_timestamp <= ? and not migrating;");

  
    // @formatter:on

    private static final int MAX_RECORDS_PER_DRONE = 10;

    /**
     * Report the location of a drone and take any required actions.
     * 
     * @param droneId
     * @param latitude
     * @param longitude
     * @param speedMps
     * @return VoltTable[]
     * @throws VoltAbortException
     */
    public VoltTable[] run(long droneId, double latitude, double longitude, int speedMps) throws VoltAbortException {

        // Create a Geography Point object from our co-ordinates
        final GeographyPointValue longLat = new GeographyPointValue(longitude, latitude);

        // Upsert drone record so it exists with appropriate date
        voltQueueSQL(upsertDrone, droneId);
        
        // Add to drone_locations
        voltQueueSQL(addLocation, droneId, getTransactionTime(), longLat, speedMps);
        
        // See if this drone is too close to an important place - we insert into an export stream
        voltQueueSQL(findIncursions, droneId, getTransactionTime());
        
        // Find n'th oldest record - we allow for MAX_RECORDS_PER_DRONE records.
        voltQueueSQL(findDeletePoint, droneId, MAX_RECORDS_PER_DRONE);

        VoltTable[] results = voltExecuteSQL();

        // if there is an n'th oldest record...
        if (results[3].advanceRow()) {
            
            // migrate that record and the ones older than it to the export stream...
            voltQueueSQL(migrateOldRecords, droneId, results[3].getTimestampAsTimestamp("event_timestamp"));
            return voltExecuteSQL(true);
        }

        return results;
    }

}
