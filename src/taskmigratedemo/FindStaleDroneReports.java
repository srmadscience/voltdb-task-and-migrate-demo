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
import org.voltdb.types.TimestampType;

/**
 * Example of a DIRECTED PROCEDURE that finds drones that haven't reported for a
 * while and logs them to an export stream
 * 
 * @author drolfe
 *
 */
public class FindStaleDroneReports extends VoltProcedure {

    // @formatter:off 
    
    // See if any drones *might* be missing before we invest time and energy doing anything
    // else
    public static final SQLStmt getMostMissingDroneDate = new SQLStmt("select declare_missing_date "
            + "from missing_drone_maxdate where declare_missing_date < NOW;");
    
    // Find drones and last known locations where 'declare_missing_date' is in the past.
    public static final SQLStmt findMissingDrones = new SQLStmt("select dl.* from drone_locations dl "
            + "   , drones d    , latest_drone_activity lda "
            + "where dl.drone_id = d.drone_id "
            + "and   dl.drone_id = lda.drone_id "
            + "and   dl.event_timestamp = lda.event_timestamp "
            + "and   d.declare_missing_date IS NOT NULL "
            + "AND d.declare_missing_date  <=  ? "
            + "and d.declare_missing_date >= dateADD(MILLISECOND, -500, ?) "
            + "order by dl.drone_id, dl.event_timestamp LIMIT ?;");

    // Mark the drone as 'missing' so we don't find it again
    public static final SQLStmt updateDrone = new SQLStmt(
            "update drones set declare_missing_date = null where drone_id = ?;");

    // Report it as missing by inserting into the missing_drones export stream
    public static final SQLStmt reportMissing = new SQLStmt(
            "insert into missing_drones (drone_id,event_timestamp, drone_location,drone_speed_mps ) values (?,?,?,?)");
    
    // @formatter:on 

    private static final int MAX_DRONES_PER_PASS = 300;

    /**
     * Find drones that haven't reported in a while and log them to an export
     * stream, but only once.
     * 
     * @return VoltTable[]
     * @throws VoltAbortException
     */
    public VoltTable[] run() throws VoltAbortException {

        voltQueueSQL(getMostMissingDroneDate);
        VoltTable[] results = voltExecuteSQL();

        if (results[0].advanceRow()) {

            // get max date - task may have been stopped for some reason,
            // in which case it would be a while ago...
            final TimestampType maxDate = results[0].getTimestampAsTimestamp("declare_missing_date");

            if (maxDate != null) {
                System.out.println("X" + maxDate.toString());
                // find the first MAX_DRONES_PER_PASS missing drones...
                voltQueueSQL(findMissingDrones, maxDate, maxDate, MAX_DRONES_PER_PASS);
                results = voltExecuteSQL();

                // for each one...
                while (results[0].advanceRow()) {

                    final long droneId = results[0].getLong("DRONE_ID");
                    final TimestampType eventTimestamp = results[0].getTimestampAsTimestamp("EVENT_TIMESTAMP");
                    final GeographyPointValue droneLocation = results[0].getGeographyPointValue("DRONE_LOCATION");
                    final long speedMps = results[0].getLong("DRONE_SPEED_MPS");

                    // Mark drone as 'missing' so we don't find it again on the
                    // next
                    // pass
                    voltQueueSQL(updateDrone, droneId);

                    // Report it as missing by adding it to the 'missing_drones'
                    // export stream
                    voltQueueSQL(reportMissing, droneId, eventTimestamp, droneLocation, speedMps);
                }

                voltExecuteSQL(true);
            }else {
                System.out.println("max date is null");
            }

        } else {
            System.out.println("No max date in past");
        }

        return results;
    }

}
