#!/bin/bash

# Simple script to run the DiskFreeChecker

BASEDIR=`dirname $0`
cd ${BASEDIR}

if [ -f ${BASEDIR}/DiskFreeChecker.jar ]; then
	
	# run as .jar file
	java -jar $BASEDIR/DiskFreeChecker.jar $*
	
elif [ -f ${BASEDIR}/DiskFreeChecker.class ]; then
	
	# find MySQL JDBC driver
	JDBC_JAR=$(find ${BASEDIR} /usr/share/java -type f -name 'mysql-connector*.jar' | head -n1)

	# run as .class file
	java -cp $BASEDIR:${JDBC_JAR} DiskFreeChecker $*
	
else 
	
	echo "DiskFreeChecker .class or .jar not found."
	echo "Run ${BASEDIR}/build.sh first."
	exit 1
	
fi
