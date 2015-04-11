#!/bin/bash

# this script will bundle the DiskFreeChecker
# along with the mysql JDBC driver and 
# put it in a new DiskFreeChecker.jar

BASEDIR=`dirname $0`
cd ${BASEDIR}

# compile first
./build.sh
if [ $? -ne 0 ]; then
    echo "Compile Failed"
    exit
fi

# make temporary build directory
mkdir .build
cd .build

# bundle the app with along with the JDBC driver
JDBC_JAR=$(find .. -type f -name 'mysql-connector*.jar')
jar xf ${JDBC_JAR}
mv ../DiskFreeChecker*.class .
jar cfm ../DiskFreeChecker.jar ../Manifest.txt *

# clean up
cd ..
/bin/rm -r .build

# if we have our new jar file then explain it's usage
if [ -f DiskFreeChecker.jar ];
then
	echo "DiskFreeChecker.jar is now bundled with the JDBC Driver."
	echo "Make sure the .jar and .properties files are together"
	echo "in the same directory and run:"
	echo ""
	echo "    java -jar DiskFreeChecker {params}"
	echo ""
fi
