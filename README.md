# voltdb-task-and-migrate-demo

This demo showcases some VoltDB features:

* The [MIGRATE](https://docs.voltdb.com/UsingVoltDB/sqlref_migrate.php) command.
* [Directed Procedures](https://docs.voltdb.com/UsingVoltDB/SimpleDirectedProcs.php) and Scheduled [Tasks](https://docs.voltdb.com/UsingVoltDB/ddlref_createtask.php).

## Scenario 

We are pretending to be a system for detecting rogue drones in central London. Various locations 
are forbidden to drones. We need to raise the alarm if a drone gets too close to one of these locations.
We also need to raise an alarm if we stop getting reports for a drone. We are expecting 10's of thousands of position reports per second.

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

The application has two stored procedures: 

### ReportLocation

'ReportLocation' is fed mock data by the test client ([TaskMigrateDemoClient](https://github.com/srmadscience/voltdb-task-and-migrate-demo/blob/master/src/org/voltdb/tastmigratedemo/TaskMigrateDemoClient.java)), but in a real world deployment would be configured to read messages from a queue such as Kafka or Kinesis.

[ReportLocation](https://github.com/srmadscience/voltdb-task-and-migrate-demo/blob/master/src/taskmigratedemo/ReportLocation.java) takes a position report for a drone and updates the database. It also:

* Checks to see if the drone is too close to an important_location.
* [MIGRATES](https://docs.voltdb.com/UsingVoltDB/sqlref_migrate.php) any extra drone_location records

This is called repeatedly from the demo's client program.

### FindStaleDroneReports

[FindStaleDroneReports](https://github.com/srmadscience/voltdb-task-and-migrate-demo/blob/master/src/taskmigratedemo/FindStaleDroneReports.java) finds any drones that have failed to report for too long a time period and writes a message to missing_drone_stats. It also updates the drone record to prevent duplicate reports. FindStaleDroneReports is run as a [TASK](https://docs.voltdb.com/UsingVoltDB/ddlref_createtask.php) every 250 milliseconds

## Installation and setup

### VoltDB

See [here](https://www.voltdb.com/try-voltdb/).

### Dependencies

This project needs [voltdb-schemabuilder](https://github.com/srmadscience/voltdb-schemabuilder).

### Configure Export Streams

In order to fully observe the functionality of this demo you'll need to get export up and running. This can be done in two ways:

#### Configure Streams from within the VoltDB GUI

![Image of streams being configured](https://github.com/srmadscience/voltdb-task-and-migrate-demo/blob/master/doc/export_streams_parameters.png)


#### Configure streams by shutting down the database and editing deployment.xml


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
 
 An example would be:
 
 ```
 TaskMigrateDemoClient 192.168.0.50,192.168.0.51 50000 10000000 1800
 ````
 
 
 
 