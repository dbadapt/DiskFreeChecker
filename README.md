# DiskFreeChecker - Report growth of disk storage over time

This is a Java-MySQL example utility used in the Percona Live 2015 Java MySQL 101 presentation.

It demonstrates the use of MySQL with Java by implementing some of these basic concepts:

* Load JDBC configuration from a Java properties file
* Query with ResultSet
* Use and Reuse of PreparedStatement
* Dynamic DDL creation
* Batch inserts using addBatch/executeBatch
* Use a ShutdownHook to cleanup connections

The DiskFreeChecker utility has a few other features that make it unique.  

* No compile-time dependencies other than the JDK
* Single java file with no inheritance for easy reading
* Runs under a normal user account, no special permissions required
* Basic shell-script build environment   

## Dependencies

- JDK 1.5 or greater to compile
- A MySQL JDBC driver compatible with Connector/J
- Access to a MySQL server to store data
- The POSIX **df** utility on the local system

## Compiling 

Use the build.sh script to compile the utility.

    ./build.sh
    
This script will create a series of DiskFreeChecker*.class files for 
the main class and other internal classes.     
    
You can bundle the utility with a MySQL JDBC driver in order to run as a 
self-standing jar with no additional **CLASSPATH** dependencies outside of
the standard JRE/JDK

    ./bundle.sh

This will create a DiskFreeChecker.jar file that contains the utility
along with the JDBC driver.  This will allow execution without specifying
a JDBC driver in with the **-cp** option or **CLASSPATH** environment 
variable. 

## Configuring

The utility is configured through the DiskFreeChecker.properties file.
This file contains the MySQL connection properties along with the
parameters that control the service checking interval, default reporting
period and data cleanup window.

DiskFreeChecker.properties should be placed in the same directory with
the **DiskFreeChecker*.class** files or the **DiskFreeChecker.jar** file if
the bundled option is used.

## Running the service

This utility runs in multiple modes specified on the command line:

### service mode

This runs the service that checks **df** statistics and records them
in a MySQL database table on regular intervals.
  
    $ ./DiskFreeChecker.sh service > DiskFreeChecker.log &

By default, the utility will check the *df* stats every 60 seconds.  This
can be changed in the **DiskFreeChecker.properties** file by modifying
the value of **check.seconds**.

### report mode

After the service as been started, the report mode can be used to 
check disk storage growth over time. For example:

    $ ./DiskFreeChecker.sh report 24
    
This will compare the systems disk storage from 24 hours ago to the present
time.  If the service was not recording disk utilization at the specified
time,  the utility will find the closest time previous to or after the
specified time window in that order.  By default, the utility reports back 24
hours or a close as possible.  This can be changed in the
**DiskFreeChecker.properties** file by modifying the value of **report.back**
 
Example output:

		Hostname: myhost
		    From: 2015-04-09 22:16:12.0
		      To: 2015-04-11 10:31:40.0
		   Hours: 36.26
		
		     1K-Used      1K-Free  Use%    1K-Change  Growth% Mounted on
		           0      8180296    0%            0        - /sys/fs/cgroup
		           0      8170356    0%            0        - /dev
		        1332      8178964    1%          -40  -0.029% /run
		       20732      8159564    1%           28   0.002% /dev/shm
		    22590708     72933564   24%       214032   0.010% /
		   444311148     54509268   90%       481116   0.002% /usr/local/backup
		       60928    129604296    1%            0   0.000% /ssd
		      148504       309452   33%            0   0.000% /boot
		       36368      8143928    1%         3908   0.121% /tmp
		   360318928    117898520   76%       179100   0.001% /home
		      564716    433514064    1%           20   0.001% /opt


### cleanup mode

This mode is designed to be added to a regularly scheduled cron job in order
to cleanup old usage statistics.

      $ ./DiskFreeChecker.sh cleanup
      
By default, the utility will cleanup statistics older than 30 days.  The can
be changed in the **DiskFreeChecker.properties** file by changing the value of
**keep.days**

