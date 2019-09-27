#!/bin/sh

JAVA=/home/keonn/java/bin/java
$JAVA -Dcom.keonn.os.io.SampleDelay=1000 -Dcom.keonn.os.io.SamplePeriod=30 -Dcom.keonn.system.defs=adrd-150-series -Dcom.keonn.system.model=0091102 -Dgnu.io.rxtx.SerialPorts=/dev/ttyO1 -Djava.library.path=native-lib \
-cp lib/mqtt-client-0.4.0.jar:lib/slf4j-api-1.6.1.jar:lib/JCLAP-1.1.jar:lib/bsh-core-2.0b4.jar:lib/RXTXcomm.jar:lib/keonn-adrd.jar:lib/keonn-util.jar:lib/keonn-framework.jar:lib/keonn-spec.jar:lib/keonn-core.jar:lib/AdvanNetLib.jar:bin/ \
$*
