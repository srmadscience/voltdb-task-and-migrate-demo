# voltdb-task-and-migrate-demo

This demo showcases some VoltDB features:

* The MIGRATE command
* Directed Procedures and Scheduled Tasks

## Scenario 

We are pretending to be a system for detecting rogue drones in central London. Various locations 
are forbidden to drones. We need to raise the alarm if a drone gets too close to one of these locations.
We also need to raise an alarm if we stop getting reports for a drone.

## Schema

We have the following tables, views and export streams:

| Name | Type | Purpose |
| ---  | ---  | ---     |
| Drones | Table | Master table for drones. Used for tracking when to declare it missing. |
| Important_Locations | Table | Places in Central London we want to keep an eye on |
| drone_locations | Table | Keeps last 10 position reports for each drone. Extra rows are MIGRATED to old_drone_locations_tgt |
| missing_drones | Export Stream | We add a record every time a drone stops sending us location information |
|  location_incursions | Export Stream | We add a record every time a drone gets too close to a location mentioned in Important_locations |
| old_drone_locations_tgt | Export Stream | Where old drone_location records go. Defined in the DDL for DRONE_LOCATIONS |
| drone_activity | View | Shows how many drones have reported positions over the last few minutes |
| missing_drone_stats | View | Shows how many drones will be declared missing over the next few minutes if we don't get a location report |
| missing_drone_maxdate | View | A single row that tells us whether we need to search for missing drones |
| latest_drone_activity | View | A summary showing the latest location report time for each drone |


## Procedures

The application has two stored procedures

### ReportLocation

This takes a position report for a drone and updates the database. It also:

* Checks to see if the drone is too close to an important_location.
* MIGRATES any extra drone_location records

This is called repeatedly from the demo's client program.

### FindStaleDroneReports

This finds any drones that have failed to report for too long a time period and writes a message to missing_drone_stats. It also updates the drone record to prevent duplicate reports.

## Installation and setup

### VoltDB

See here.

### Dependencies

This project needs voltdb-schemabuilder.

### Configure Export Streams


````
 <export>
        <configuration target="old_drone_locations_tgt" enabled="true" type="file" exportconnectorclass="" threadpool="">
            <property name="type">csv</property>
            <property name="nonce">odlt</property>
            <property name="outdir">/Users/drolfe/csv</property>
        </configuration>
        <configuration target="tgt_missing_drones" enabled="true" type="file" exportconnectorclass="" threadpool="">
            <property name="type">csv</property>
            <property name="nonce">md</property>
            <property name="outdir">/Users/drolfe/csv</property>
        </configuration>
        <configuration target="location_incursions_tgt" enabled="true" type="file">
            <property name="type">csv</property>
            <property name="nonce">lit</property>
            <property name="outdir">/Users/drolfe/csv</property>
        </configuration>
    </export>
 ````
 
 ### Run the code
 
 The client program is called TaskMigrateDemoClient and takes the following parameters:
 
 | Parameter | Purpose | Example |
 | ---       | ---     | ---     |
 | hostnames | comma delimited list of VoltDB hosts | 127.0.0.1 |
 | tps | How many transactions per second to generaye | 30000 |
 | size | How many drones to track | 1000000 |
 | seconds | How many seconds to run for | 180 |
 
 
 