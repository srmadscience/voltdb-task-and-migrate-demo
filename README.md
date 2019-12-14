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
 