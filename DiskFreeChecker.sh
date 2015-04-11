#!/bin/bash

# Simple script to run the DiskFreeChecker

BASEDIR=`dirname $0`
cd ${BASEDIR}

if [ ! -f ${BASEDIR}/DiskFreeChecker.class ];
then
	echo "DiskFreeChecker class not found."
	echo "Run ${BASEDIR}/build.sh first."
	exit 1
fi

JDBC_JAR=$(find ${BASEDIR} -type f -name 'mysql-connector*.jar')

java -cp $BASEDIR:${JDBC_JAR} DiskFreeChecker $*
