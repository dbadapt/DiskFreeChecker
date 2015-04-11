#!/bin/bash

# Simple script to compile our class

BASEDIR=`dirname $0`
cd ${BASEDIR}
echo Compiling...
javac DiskFreeChecker.java
