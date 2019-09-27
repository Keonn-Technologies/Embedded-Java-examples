#!/bin/sh

JAVA=/opt/java/bin/java
$JAVA -Dcom.keonn.os.io.SampleDelay=1000 -Dcom.keonn.os.io.SamplePeriod=30 -Dcom.keonn.system.defs=adrd-150.04-series -Dgnu.io.rxtx.SerialPorts=/dev/ttyO1:/dev/ttyO2 -Djava.library.path=native-lib \
-cp lib/*:bin/ \
$*
